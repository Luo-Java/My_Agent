package org.luo.common.result;

import lombok.Getter;

/**
 * 统一响应体：{@code {code, message, data}}。
 * <p>
 * 成功码固定 {@link #SUCCESS_CODE}（200，与 HTTP 状态码对齐）；失败码取自
 * {@link org.luo.common.exception.AiErrorCode}，由全局异常处理统一构造，
 * 成功与失败两条路径共用同一形状。
 *
 * @param <T> 业务数据类型
 */
@Getter
public class RestResult<T> {

    /** 成功状态码（与 HTTP 200 对齐）。 */
    public static final int SUCCESS_CODE = 200;

    private final int code;
    private final String message;
    private final T data;

    private RestResult(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** 成功，无数据。 */
    public static <T> RestResult<T> ok() {
        return new RestResult<>(SUCCESS_CODE, "ok", null);
    }

    /** 成功，携带数据。 */
    public static <T> RestResult<T> ok(T data) {
        return new RestResult<>(SUCCESS_CODE, "ok", data);
    }

    /** 失败，仅由全局异常处理调用。 */
    public static <T> RestResult<T> fail(int code, String message) {
        return new RestResult<>(code, message, null);
    }
}
