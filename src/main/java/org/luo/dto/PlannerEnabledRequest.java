package org.luo.dto;

/**
 * 更新会话智能规划开关的请求体（与 RAG 开关对称的纯布尔开关，拨动即写回会话）。
 *
 * @param enabled true=开启规划模式（动态规划器多智能体编排）；false=关闭（普通对话/智能路由）
 */
public record PlannerEnabledRequest(Boolean enabled) {
}
