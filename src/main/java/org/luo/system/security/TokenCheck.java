package org.luo.system.security;

/**
 * token 校验结果：把「为什么没通过」显式带出来。
 * <p>
 * 此前 {@code parseUserId} 只返回 {@code Long}（无效即 null），调用方只能笼统回一句
 * 「登录已失效，请重新登录」—— 究竟是<b>没带凭证</b>、<b>签名不对（密钥换过）</b>、
 * <b>过期</b>还是<b>结构畸形</b>，服务端日志与前端提示都无从区分，排查只能靠猜。
 * 这里用状态枚举把结论固定下来：拦截器写进响应体 {@code reason} 字段并落 WARN 日志。
 */
public record TokenCheck(TokenStatus status, Long userId) {

    /** 校验结论。 */
    public enum TokenStatus {
        /** 通过 */
        OK,
        /** 请求里没有凭证：前端没带、或中间层把 Authorization 头吃掉了 */
        NO_TOKEN,
        /** 结构畸形：不是三段式、或 base64 解不开 */
        MALFORMED,
        /** 签名不通过：签名密钥变过（改了 app.jwt.secret、或未配置时每次启动随机），或 token 被篡改 */
        BAD_SIGNATURE,
        /** 缺有效期声明：非本系统签发，或旧格式的 token */
        MISSING_EXPIRY,
        /** 已过期 */
        EXPIRED,
        /** 身份字段不可用：sub 缺失或不是数字 */
        NO_SUBJECT
    }

    public static TokenCheck ok(long userId) {
        return new TokenCheck(TokenStatus.OK, userId);
    }

    public static TokenCheck fail(TokenStatus status) {
        return new TokenCheck(status, null);
    }

    /** 是否通过。 */
    public boolean valid() {
        return status == TokenStatus.OK;
    }
}
