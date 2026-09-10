package org.luo.exception;

import lombok.Getter;
import org.luo.config.GlobalExceptionHandler;

/**
 * AI 应用业务异常：业务校验/流程失败时统一抛出。
 * <p>
 * 业务代码不要抛散乱的 IllegalArgumentException 等通用异常，
 * 统一抛本异常，由 GlobalExceptionHandler 按 {@link AiErrorCode} 转成对应 HTTP 状态码 + 友好提示。
 * 未指定状态码时默认 {@link AiErrorCode#INTERNAL_ERROR}（500）。
 */
@Getter
public class AiBusinessException extends RuntimeException {

    private final AiErrorCode errorCode;

    /** 默认按 500 处理。 */
    public AiBusinessException(String message) {
        this(AiErrorCode.INTERNAL_ERROR, message);
    }

    /** 使用枚举默认提示语。 */
    public AiBusinessException(AiErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage());
    }

    /** 指定状态码 + 自定义提示语。 */
    public AiBusinessException(AiErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /** 指定状态码 + 自定义提示语 + 原始异常。 */
    public AiBusinessException(AiErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** 便捷获取 HTTP 状态码。 */
    public int getCode() {
        return errorCode.getCode();
    }
}
