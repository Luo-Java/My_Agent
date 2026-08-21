package org.luo.dto;

/**
 * AI 生成智能体提示词请求体。
 *
 * @param name        智能体名称（必填，作为生成依据）
 * @param description 智能体描述（可选，帮助 AI 理解角色定位）
 */
public record GeneratePromptRequest(
        String name,
        String description
) {
}
