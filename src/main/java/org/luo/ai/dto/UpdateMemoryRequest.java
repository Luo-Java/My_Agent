package org.luo.ai.dto;

/**
 * 覆写会话长期记忆请求体。
 * <p>
 * <b>只覆盖内容、不动水位</b>（{@code summarized_count} 由系统维护，见
 * {@code ConversationService.updateMemoryFields}）。两个字段都传 null / 空白即等于「清空记忆内容、
 * 保留游标」；要连游标一起归零请走 {@code DELETE …/memory}。
 *
 * @param summary   新的滚动摘要；null 或空白 = 清空该字段
 * @param coreFacts 新的核心事实；null 或空白 = 清空该字段
 */
public record UpdateMemoryRequest(String summary, String coreFacts) {
}
