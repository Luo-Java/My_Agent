package org.luo.ai.dto;

/**
 * 更新会话「⚖ 评审」开关的请求体（与 RAG / 规划开关同构的纯布尔开关，拨动即写回会话）。
 * <p>
 * 开启后本轮由多个候选智能体并行作答、再由裁决者综合；与规划模式互斥（开启评审会自动关掉规划）。
 *
 * @param enabled true=启用并行评审；false=普通单智能体回答
 */
public record ReviewEnabledRequest(Boolean enabled) {
}
