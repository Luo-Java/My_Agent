package org.luo.ai.dto;

/**
 * 更新规划模式「先看计划」开关的请求体（与规划 / RAG 开关同构的纯布尔开关，拨动即写回会话）。
 *
 * @param enabled true=规划只产出计划并暂停，用户确认后才执行；false=规划后直接执行
 */
public record PlannerConfirmRequest(Boolean enabled) {
}
