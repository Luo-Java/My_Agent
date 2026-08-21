package org.luo.dto;

import java.time.LocalDateTime;

/**
 * 创建会话的返回体。
 *
 * @param conversationId 新会话 ID
 * @param title          会话标题
 * @param createdAt      创建时间
 * @param agentId        绑定的智能体 ID（未绑定则为 null）
 */
public record NewConversationResponse(String conversationId, String title, LocalDateTime createdAt, Long agentId) {
}
