package org.luo.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.luo.common.result.PageResult;
import org.luo.common.util.PiiMasker;
import org.luo.common.util.TextClip;
import org.luo.system.entity.AuditLog;
import org.luo.system.mapper.AuditLogMapper;
import org.luo.system.security.AuthContext;
import org.luo.system.security.LoginUser;
import org.luo.system.service.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

/**
 * {@link AuditService} 实现。
 * <p>
 * 三条实现纪律：
 * <ol>
 *   <li><b>失败不拖垮业务</b>：落库异常一律 catch 后记 ERROR，业务继续。审计的调用点在业务写操作
 *       <b>之后</b> —— 业务中途抛异常时就到不了这里，因此不会出现「操作没生效、审计却记了一笔」的假记录。
 *       （注意别把它读成事务保证：本模块的 service 方法都没有 {@code @Transactional}，
 *       一次请求里的多次写入本就不是原子的。）</li>
 *   <li><b>detail 先脱敏再落库</b>：审计抄了一份「被改的东西」，原样抄会让审计表自己变成敏感信息聚集地 ——
 *       一边做数据安全一边造窟窿。写入前统一过 {@link PiiMasker}。副作用要说清楚：用户资料里的邮箱
 *       也会一并被遮（如 {@code zh******@example.com}），因为脱敏按「内容长什么样」判定，不区分字段 ——
 *       要看原值请去 {@code sys_user}，审计只负责说明「改过什么」。</li>
 *   <li><b>不引 AOP 注解</b>：本项目全链路不引 AOP（{@code @RequireRole} 走拦截器读注解，见
 *       {@code JwtAuthInterceptor}）。审计在少数几个 service 方法里显式调用，好处是<b>记录点一眼可见</b> ——
 *       注解式审计最难查的问题恰恰是「这个方法到底有没有生效」。</li>
 * </ol>
 */
@Slf4j
@Service
public class AuditServiceImpl implements AuditService {

    /** detail 列宽（{@code VARCHAR(1000)}）：超长入库会被 MySQL 截断或报错，故在写入前显式截断并留标记。 */
    private static final int DETAIL_MAX = 1000;
    /** action 列宽。 */
    private static final int ACTION_MAX = 64;

    /**
     * ip 列宽（{@code VARCHAR(64)}）。
     * <p>
     * 来源含 {@code X-Forwarded-For}（<b>客户端可控</b>），不 clip 就能被构造请求打出一场
     * 「MySQL 1406 → record() 吞异常 → 这条操作悄悄没有留痕」，而表是只增不改的，无从补救。
     */
    private static final int IP_MAX = 64;
    /** 单页条数上限（与项目分页口径一致：上限 100，防一次拉全表）。 */
    private static final int PAGE_SIZE_MAX = 100;
    /** 单页条数缺省。 */
    private static final int PAGE_SIZE_DEFAULT = 20;

    private final AuditLogMapper mapper;

    public AuditServiceImpl(AuditLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void record(String action, String targetType, Object targetId, String detail) {
        try {
            LoginUser me = AuthContext.get();
            AuditLog row = new AuditLog();
            if (me != null) {
                row.setUserId(me.id());
                row.setUsername(me.username());
            }
            row.setAction(clip(action, ACTION_MAX));
            row.setTargetType(targetType);
            row.setTargetId(targetId == null ? null : String.valueOf(targetId));
            row.setDetail(clip(PiiMasker.mask(detail), DETAIL_MAX));
            row.setIp(clientIp());
            row.setCreatedAt(LocalDateTime.now());
            mapper.insert(row);
        } catch (Exception e) {
            log.error("审计落库失败（本次操作不受影响，但这笔操作没有留痕）：action={}，target={}/{}，原因={}",
                    action, targetType, targetId, e.getMessage());
        }
    }

    @Override
    public PageResult<AuditLog> page(int page, int size, String action) {
        int p = Math.max(1, page);
        int s = size <= 0 ? PAGE_SIZE_DEFAULT : Math.min(size, PAGE_SIZE_MAX);
        LambdaQueryWrapper<AuditLog> q = new LambdaQueryWrapper<>();
        if (action != null && !action.isBlank()) {
            q.eq(AuditLog::getAction, action.strip());
        }
        // 按 id 倒序而不是 created_at：自增主键即时间序，且同秒写入的相邻记录顺序稳定（created_at 做不到）
        q.orderByDesc(AuditLog::getId);
        return PageResult.of(mapper.selectPage(new Page<>(p, s), q));
    }

    /**
     * 取请求来源 IP：优先 {@code X-Forwarded-For} 的第一段（反向代理场景），退化到 socket 地址；拿不到返回 null。
     * <p>
     * <b>为什么要 clip 到列宽</b>：XFF 是<b>客户端可控</b>的请求头，构造一个超长值就能让 insert 撞
     * {@code VARCHAR(64)}。而 {@link #record} 的 catch 会把这类失败吞掉（本意是「审计失败不拖垮业务」），
     * 后果是<b>这一条操作彻底没有留痕</b> —— 对一张只增不改的表来说，「悄悄少一条」比「报错」更坏，
     * 因为它不留任何痕迹让人知道丢了。
     */
    private static String clientIp() {
        try {
            ServletRequestAttributes attr =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attr == null) {
                return null;   // 非 HTTP 线程（定时任务等）
            }
            HttpServletRequest req = attr.getRequest();
            if (req == null) {
                return null;
            }
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                int comma = xff.indexOf(',');
                return clip((comma > 0 ? xff.substring(0, comma) : xff).strip(), IP_MAX);
            }
            return clip(req.getRemoteAddr(), IP_MAX);
        } catch (Exception e) {
            return null;
        }
    }

    /** 截断到列宽（超长时保留省略标记，便于一眼看出「被截过」）。实现见 {@link TextClip}。 */
    private static String clip(String s, int max) {
        return TextClip.clip(s, max, TextClip.ELLIPSIS);
    }
}
