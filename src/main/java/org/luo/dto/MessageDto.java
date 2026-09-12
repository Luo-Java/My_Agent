package org.luo.dto;

import java.util.List;

/**
 * 历史消息项。
 *
 * @param role        user / assistant
 * @param content     消息内容
 * @param attachments 本轮附件展示元数据（仅 user 消息可能有；无附件为空列表）。
 *                    仅用于历史渲染，不参与 LLM 上下文。
 * @param citations   本轮 RAG 引用来源（仅 assistant 消息可能有；无引用为空列表）。
 *                    仅用于历史渲染 [n] 角标与来源列表，不参与 LLM 上下文。
 */
public record MessageDto(String role, String content, List<AttachmentDto> attachments, List<KbCitation> citations) {

    /** 无附件、无引用的便捷构造（旧数据 / 无需展示溯源的消息）。 */
    public MessageDto(String role, String content) {
        this(role, content, List.of(), List.of());
    }
}
