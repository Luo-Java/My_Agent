package org.luo.common.exception;

import lombok.Getter;

/**
 * AI 应用异常状态码枚举：集中定义业务/HTTP 错误码与默认提示语。
 * <p>
 * 业务代码抛 {@link AiBusinessException} 时携带该枚举，全局异常处理据此返回对应 HTTP 状态码；
 * 未显式指定的业务异常默认 {@link #INTERNAL_ERROR}（500）。
 */
@Getter
public enum AiErrorCode {

    /** 请求参数缺失、格式错误或业务校验不通过。 */
    BAD_REQUEST(400, "请求参数错误"),

    /** 未登录或凭证缺失/失效。 */
    UNAUTHORIZED(401, "未授权，请先登录"),

    /** 已登录但无权限访问该资源。 */
    FORBIDDEN(403, "禁止访问"),

    /** 目标资源不存在。 */
    NOT_FOUND(404, "资源不存在"),

    /** 资源状态冲突（如重复创建、版本过期）。 */
    CONFLICT(409, "资源状态冲突"),

    /** 用量超出配额（如当日 token 配额用尽）：请求本身合法，是额度不够，故单独一档而非 400。 */
    QUOTA_EXCEEDED(429, "用量已超出配额，请稍后再试"),

    /**
     * 内容被安全策略拦截（{@code agent.safety.*} 的规则命中）。
     * <p>
     * 用 422（语义为「请求格式正确、但内容无法处理」）而非 400：请求本身没有语法错误，
     * 是内容不符合规范；与 429 同属「请求合法但当前不被接受」这一档。
     */
    CONTENT_BLOCKED(422, "内容不符合使用规范，已被安全策略拦截"),

    /** 服务器内部错误（默认，未归类异常）。 */
    INTERNAL_ERROR(500, "服务器内部错误，请稍后重试");

    /** HTTP 状态码。 */
    private final int code;

    /** 该错误码的默认提示语；业务可覆盖。 */
    private final String defaultMessage;

    AiErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

}
