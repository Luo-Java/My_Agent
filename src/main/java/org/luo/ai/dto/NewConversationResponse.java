package org.luo.ai.dto;

import java.time.LocalDateTime;

/**
 * 创建会话的返回体。
 *
 * @param conversationId 新会话 ID
 * @param title          会话标题
 * @param createdAt      创建时间
 * @param agentId        绑定的智能体 ID（未绑定则为 null）
 * @param planner        是否规划模式会话（true=动态规划器）
 * @param ragEnabled      会话级 RAG 开关（新会话默认 false=不检索）
 * @param plannerConfirm  规划模式「先看计划」开关（新会话默认 false=规划后直接执行）
 * @param reviewEnabled   并行评审开关（新会话默认 false=普通单智能体回答）
 * @param crossSession    跨会话搜索开关（新会话默认 false=不检索他人历史）
 */
public record NewConversationResponse(String conversationId, String title, LocalDateTime createdAt, Long agentId,
                                      Boolean planner, Boolean ragEnabled, Boolean plannerConfirm,
                                      Boolean reviewEnabled, Boolean crossSession) {
}
