package org.luo.system.security;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTPayload;
import cn.hutool.jwt.JWTUtil;
import cn.hutool.jwt.signers.JWTSigner;
import cn.hutool.jwt.signers.JWTSignerUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * JWT 签发与解析：全项目唯一碰 token 编解码的地方（复用 Hutool，不引第三个 JWT 依赖）。
 * <p>
 * <b>为什么有效期用自定义声明 {@code expMs} 而不是标准的 {@code exp}：</b>
 * 标准 NumericDate 是秒级，而 Hutool 把 claims 里的 {@code Date} 序列化成什么单位属于其内部约定，
 * 依赖它会出现「单位猜错 → token 一签发就过期」这类静默故障。这里用毫秒时间戳自定义声明，
 * 单位由本类保证，校验也只认它。
 * <p>
 * token 只装「身份 + 有效期」，<b>不带权限</b>：角色由 {@code JwtAuthInterceptor} 每次请求
 * 从库里实时读取，因此改角色/停用账号立即生效，不必等 token 过期。
 */
@Slf4j
@Component
public class JwtTokenService {

    /** 自定义声明：登录名，仅供日志排查。 */
    private static final String CLAIM_USERNAME = "username";

    /** 自定义声明：过期时刻（毫秒时间戳），校验的唯一依据。 */
    private static final String CLAIM_EXPIRES_MS = "expMs";

    /** HMAC-SHA256 密钥最小长度（字节）。低于此长度视为未配置。 */
    private static final int MIN_SECRET_BYTES = 32;

    /** 有效期缺省值（分钟）：配置缺失或非法时的兜底，12 小时。 */
    private static final int DEFAULT_EXPIRE_MINUTES = 720;

    private final JwtProperties props;

    /**
     * 签名密钥（不可变）。
     * <p>
     * <b>这里只存密钥、不存 signer —— 每次签发/校验都现建一个。</b>不是图省事，是必须：
     * Hutool 的 {@code HMacJWTSigner} 内部持有<b>一个</b> {@code Mac} 实例，而
     * {@code javax.crypto.Mac} 不是线程安全的；更关键的是 Hutool 的
     * {@code verify()} 实现是「重新签一遍再比对字符串」。于是多线程并发时，各请求的
     * {@code mac.update()} / {@code doFinal()} 相互污染 —— <b>同一份合法 token 会被判成
     * 签名不符</b>。
     * <p>
     * 实测（同一份合法 token，真实编译产物，Hutool 5.8.38）：
     * <pre>
     *   单线程 × 200，共享 signer        → OK=200
     *   16 线程 × 100，共享 signer       → OK=232，BAD_SIGNATURE=1288，MALFORMED=80
     *   16 线程 × 100，每次新建 signer   → OK=1600
     *   16 线程 × 50 并发签发            → 签发即无效 600/800
     * </pre>
     * 线上症状就是「登录成功后，一进页面点几下就被要求重新登录」：页面挂载会并发发起若干
     * 接口，其中总有几个被误判为凭证无效，而前端据此清掉了（本来有效的）登录态。
     */
    private final byte[] secret;

    /**
     * 生效的 token 有效期（分钟）。
     * <p>
     * 不直接用 {@code props.getExpireMinutes()}：该字段类型是 {@code int}，配置没绑上就是 0，
     * 而 0 分钟意味着 token <b>一签发就过期</b> —— 症状是「登录成功，紧接着每个接口都要求重新登录」，
     * 且没有任何报错。这里统一收敛成正数，非法值一律回落 {@link #DEFAULT_EXPIRE_MINUTES} 并留 WARN。
     */
    private final long expireMinutes;

    public JwtTokenService(JwtProperties props) {
        this.props = props;
        this.secret = resolveSecret(props.getSecret());
        this.expireMinutes = resolveExpireMinutes(props.getExpireMinutes());
        log.info("登录鉴权已启用：token 有效期 {} 分钟（app.jwt.expire-minutes={}），签名密钥 {} 字节 / 指纹 {}",
                this.expireMinutes, props.getExpireMinutes(), secret.length, fingerprint(secret));
    }

    /**
     * 现建一个 HS256 签名器。
     * <p>
     * <b>不要把它提升成字段</b>：Hutool 的 signer 内含单个 {@code Mac}（非线程安全），
     * 共享就是并发下随机验签失败 —— 详见 {@link #secret} 的说明与实测数据。
     * 单次 Mac 初始化的代价在微秒量级，远小于一次数据库往返，不值得为它牺牲正确性。
     */
    private JWTSigner newSigner() {
        return JWTSignerUtil.hs256(secret);
    }

    /** 为登录用户签发 token。 */
    public String issue(LoginUser user) {
        long expiresAt = System.currentTimeMillis() + expireMinutes * 60_000L;
        return JWT.create()
                .setSigner(newSigner())
                .setIssuer(props.getIssuer())
                .setSubject(String.valueOf(user.id()))
                .setPayload(CLAIM_USERNAME, user.username())
                .setPayload(CLAIM_EXPIRES_MS, expiresAt)
                .sign();
    }

    /** token 有效期（秒），供登录响应回显。 */
    public long expireSeconds() {
        return expireMinutes * 60L;
    }

    /**
     * 校验 token 并取出用户ID。
     * <p>
     * 失败时返回的是<b>带原因的结论</b>（{@link TokenCheck.TokenStatus}），不再是笼统的 null：
     * 请求头被中间层吃掉（{@code NO_TOKEN}）与签名对不上（{@code BAD_SIGNATURE}）
     * 是两种完全不同的故障，处理方式也不同，必须能区分。
     * <p>
     * 注意 {@code BAD_SIGNATURE} 有两个来源：密钥换过（token 是旧密钥签的），或并发验签时的
     * 计算污染（历史缺陷，已由 {@link #newSigner()} 每请求现建 signer 修掉）。前者要重新登录，
     * 后者只需修代码 —— 用 {@link #fingerprint(byte[])} 比对启动日志即可区分。
     *
     * @return 校验结论；只有 {@link TokenCheck#valid()} 为 true 时 {@code userId} 才有值
     */
    public TokenCheck verify(String token) {
        if (token == null || token.isBlank()) {
            return TokenCheck.fail(TokenCheck.TokenStatus.NO_TOKEN);
        }
        JWT jwt;
        try {
            jwt = JWTUtil.parseToken(token);
        } catch (Exception e) {
            // 结构畸形（不是三段式 / base64 解不开）
            log.warn("token 结构畸形，无法解析：{}", e.getMessage());
            return TokenCheck.fail(TokenCheck.TokenStatus.MALFORMED);
        }
        boolean signed;
        try {
            // 每次现建 signer：共享实例会让并发请求互相污染 HMAC 计算（见 newSigner()）
            signed = jwt.setSigner(newSigner()).verify();
        } catch (Exception e) {
            log.warn("token 验签异常：{}", e.getMessage());
            return TokenCheck.fail(TokenCheck.TokenStatus.MALFORMED);
        }
        if (!signed) {
            return TokenCheck.fail(TokenCheck.TokenStatus.BAD_SIGNATURE);
        }
        JWTPayload payload = jwt.getPayload();
        long expiresAt = Convert.toLong(payload.getClaim(CLAIM_EXPIRES_MS), 0L);
        if (expiresAt <= 0) {
            log.warn("token 缺少 {} 声明（非本系统签发或旧格式），视为无效", CLAIM_EXPIRES_MS);
            return TokenCheck.fail(TokenCheck.TokenStatus.MISSING_EXPIRY);
        }
        if (System.currentTimeMillis() > expiresAt) {
            return TokenCheck.fail(TokenCheck.TokenStatus.EXPIRED);
        }
        Long userId = Convert.toLong(payload.getClaim(JWTPayload.SUBJECT));
        if (userId == null) {
            log.warn("token 缺少可用的 sub 声明，视为无效");
            return TokenCheck.fail(TokenCheck.TokenStatus.NO_SUBJECT);
        }
        return TokenCheck.ok(userId);
    }

    /**
     * 取签名密钥：配置了足够长度的密钥就用它，否则生成随机密钥。
     * <p>
     * 随机密钥是「能跑起来但不适合部署」的兜底 —— 重启后此前签发的 token 全部失效，
     * 所以这里必须留下 WARN，不能静默。
     */
    private static byte[] resolveSecret(String configured) {
        if (configured != null && configured.getBytes(StandardCharsets.UTF_8).length >= MIN_SECRET_BYTES) {
            return configured.getBytes(StandardCharsets.UTF_8);
        }
        log.warn("app.jwt.secret 未配置或不足 {} 字节，已生成随机签名密钥："
                + "本次启动签发的 token 在应用重启后全部失效，需要重新登录。"
                + "部署时请用环境变量 JWT_SECRET 注入固定密钥（例如 openssl rand -hex 32）。", MIN_SECRET_BYTES);
        return RandomUtil.randomBytes(MIN_SECRET_BYTES);
    }

    /**
     * 密钥指纹：SHA-256 前 8 位十六进制。
     * <p>
     * 只为回答一个排查问题 ——「这次启动用的还是上次那把密钥吗」。{@code BAD_SIGNATURE} 的常见来源
     * 是「请求里的 token 是用另一把密钥签的」，可比对的锚点必须是**跨重启稳定且不可逆**的：
     * 密钥本身不能进日志（它就是伪造 token 的凭据），完整哈希又等于多存一份凭据，
     * 所以取短指纹 —— 足够区分「换没换」，不足以还原任何东西。
     * <p>
     * 指纹<b>没变</b>却仍出现 {@code BAD_SIGNATURE} ⇒ 不是密钥问题（见 {@link #newSigner()} 的并发说明）。
     */
    private static String fingerprint(byte[] secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret);
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，理论到不了这里；真到了也不能因为日志而启动失败
            return "unavailable";
        }
    }

    /**
     * 取生效的有效期（分钟）：非正数一律回落默认值。
     * <p>
     * 0 分钟不是「永不过期」也不是「无意义」——它会让每个 token 在签发那一刻就过期，
     * 表现为「登录成功后进入页面又让登录」，而且因为没有异常，排查时极易误判成前端问题。
     * 所以这里必须纠偏并且不能静默。
     */
    private static long resolveExpireMinutes(int configured) {
        if (configured > 0) {
            return configured;
        }
        log.warn("app.jwt.expire-minutes={} 不是正数（配置未绑定时 int 默认为 0），"
                + "已改用默认 {} 分钟 —— 否则 token 一签发就过期，登录会立刻失效。", configured,
                DEFAULT_EXPIRE_MINUTES);
        return DEFAULT_EXPIRE_MINUTES;
    }
}
