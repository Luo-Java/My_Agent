package org.luo.ai.dto;

/**
 * 更新会话「🔎 跨会话」开关的请求体（与 RAG 开关同构的纯布尔开关）。
 * <p>
 * 开启后每轮先用 LLM 抽取检索关键词，在本用户其他会话的历史消息里做关键词召回并注入当前上下文；
 * 只查本人会话，且天然排除当前会话。
 *
 * @param enabled true=启用跨会话召回；false=不检索
 */
public record CrossSessionRequest(Boolean enabled) {
}
