package org.luo.config;

import lombok.extern.slf4j.Slf4j;
import org.luo.dto.ErrorResponse;
import org.luo.exception.AiBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：统一把异常转成 {@code {code, message}} JSON，避免裸 500 / 堆栈泄露给客户端。
 * <p>
 * 业务代码统一抛 {@link AiBusinessException}，因此这里只兜底几类常见异常：
 * 业务异常（400）、运行时异常（500）、其他异常（500）、资源不存在（404）。
 * SSE 流开始后的异常由 ChatController 的 doOnError 自行终止，不经过此处。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常（业务代码统一抛出）→ 按 {@link org.luo.exception.AiErrorCode} 返回对应 HTTP 状态码，透出提示语。 */
    @ExceptionHandler(AiBusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(AiBusinessException e) {
        int code = e.getCode();
        log.warn("业务异常：状态码={}，提示={}", code, e.getMessage());
        return ResponseEntity.status(code).body(new ErrorResponse(code, e.getMessage()));
    }

    /** 运行时异常兜底 → 500；其中请求体/参数解析类客户端错误降级为 400。 */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntime(RuntimeException e) {
        if (e instanceof HttpMessageNotReadableException || e instanceof MethodArgumentTypeMismatchException) {
            log.warn("客户端参数错误：{}", e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(400, "请求参数缺失或格式错误"));
        }
        log.error("未预期的运行时异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(500, "服务器内部错误，请稍后重试"));
    }

    /** 请求路径不存在（含静态资源未命中）→ 404。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(404, "资源不存在"));
    }

    /** 其他异常兜底（含受检异常）→ 500，不向客户端泄露内部细节。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception e) {
        log.error("未预期的异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(500, "服务器内部错误，请稍后重试"));
    }
}
