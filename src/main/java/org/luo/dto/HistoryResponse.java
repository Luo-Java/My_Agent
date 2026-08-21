package org.luo.dto;

import java.util.List;

/**
 * 会话历史返回体。
 *
 * @param conversationId 会话 ID
 * @param messages       按时间正序的消息列表
 */
public record HistoryResponse(String conversationId, List<MessageDto> messages) {
}
