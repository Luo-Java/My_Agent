package org.luo.dto;

/**
 * 对话请求体。
 *
 * @param conversationId 会话 ID，用于多轮上下文；为空时由后端使用默认会话。
 * @param message        用户输入的消息内容。
 */
public record ChatRequest(String conversationId, String message) {
}
