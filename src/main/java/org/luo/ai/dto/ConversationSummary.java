package org.luo.ai.dto;

import java.time.LocalDateTime;

/**
 * 会话列表项。
 *
 * @param id        会话 ID
 * @param title     会话标题
 * @param updatedAt 最近更新时间
 * @param agentId   绑定的智能体 ID（未绑定则为 null）
 * @param planner   是否规划模式会话（true=动态规划器）
 * @param ragEnabled 会话级 RAG 开关（true=开启自动检索，false=不使用 RAG）
 * @param plannerConfirm 规划模式「先看计划」开关（true=规划只产出计划并暂停，确认后才执行）
 * @param reviewEnabled 并行评审开关（true=多候选智能体并行作答 + 裁决者综合；与 planner 互斥）
 * @param crossSession 跨会话搜索开关（true=在本用户其他会话里做关键词召回并注入）
 */
public record ConversationSummary(String id, String title, LocalDateTime updatedAt, Long agentId, Boolean planner,
                                  Boolean ragEnabled, Boolean plannerConfirm, Boolean reviewEnabled,
                                  Boolean crossSession) {
}
