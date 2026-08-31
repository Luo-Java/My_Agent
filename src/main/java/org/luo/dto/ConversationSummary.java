package org.luo.dto;

import java.time.LocalDateTime;

/**
 * 会话列表项。
 *
 * @param id        会话 ID
 * @param title     会话标题
 * @param updatedAt 最近更新时间
 * @param agentId   绑定的智能体 ID（未绑定则为 null）
 * @param planner   是否规划模式会话（true=动态规划器）
 */
public record ConversationSummary(String id, String title, LocalDateTime updatedAt, Long agentId, Boolean planner) {
}
