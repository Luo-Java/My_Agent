package org.luo.dto;

/**
 * 更新会话 RAG 开关的请求体（纯开关，不选库）。
 *
 * @param enabled true=开启（自动检索通用知识库 + 路由到智能体时其专属库）；false=关闭
 */
public record RagEnabledRequest(Boolean enabled) {
}
