package org.luo.ai.dto;

/**
 * 重命名会话请求体。
 *
 * @param title 新的会话标题。
 */
public record RenameConversationRequest(String title) {
}
