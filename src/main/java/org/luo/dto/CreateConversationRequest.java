package org.luo.dto;

/**
 * 创建会话请求体（可选绑定智能体或规划模式，互斥）。
 *
 * @param agentId 绑定的智能体 ID（自增主键）；为空表示不绑定智能体
 * @param planner 是否规划模式会话：true=动态规划器（运行时由 LLM 规划多智能体步骤），与 agentId 互斥
 */
public record CreateConversationRequest(Long agentId, Boolean planner) {
}
