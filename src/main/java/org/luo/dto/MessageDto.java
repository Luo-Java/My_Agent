package org.luo.dto;

/**
 * 历史消息项。
 *
 * @param role    user / assistant
 * @param content 消息内容
 */
public record MessageDto(String role, String content) {
}
