package org.luo.dto;

import java.util.List;

/**
 * 历史消息项。
 *
 * @param role        user / assistant
 * @param content     消息内容
 * @param attachments 本轮附件展示元数据（仅 user 消息可能有；无附件为空列表）。
 *                    仅用于历史渲染，不参与 LLM 上下文。
 */
public record MessageDto(String role, String content, List<AttachmentDto> attachments) {

    /** 无附件的便捷构造（assistant 消息与旧数据）。 */
    public MessageDto(String role, String content) {
        this(role, content, List.of());
    }
}
