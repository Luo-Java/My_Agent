package org.luo.dto;

import java.time.LocalDateTime;

/**
 * 创建会话的返回体。
 *
 * @param conversationId 新会话 ID
 * @param title          会话标题
 * @param createdAt      创建时间
 * @param agentId        绑定的智能体 ID（未绑定则为 null）
 * @param planner        是否规划模式会话（true=动态规划器）
 */
public record NewConversationResponse(String conversationId, String title, LocalDateTime createdAt, Long agentId, Boolean planner) {
}
