package org.luo.dto;

/**
 * 创建会话请求体（可选绑定智能体）。
 *
 * @param agentId 绑定的智能体 ID（自增主键）；为空表示使用默认助手。
 */
public record CreateConversationRequest(Long agentId) {
}
