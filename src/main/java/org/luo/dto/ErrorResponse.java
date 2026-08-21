package org.luo.dto;

/**
 * 统一错误响应体：全局异常处理返回的 JSON 结构。
 *
 * @param code    业务/HTTP 错误码（如 400、404、500）
 * @param message 面向用户的错误提示
 */
public record ErrorResponse(int code, String message) {
}
