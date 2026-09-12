package org.luo.dto;

/**
 * 历史消息中的附件展示项（仅元数据，不含附件正文）。
 * <p>
 * 由 {@code chat_message.attachments_json} 解析而来，只用于历史记录渲染缩略图 / 下载链接；
 * 不参与记忆读取（DbChatMemory 只取 content），对 LLM 上下文与 token 零影响。
 *
 * @param type       附件类型：{@code image} / {@code text} / {@code file}
 * @param filename   原始文件名
 * @param storedName 落盘存储名（磁盘文件名）；为空表示原文件未落盘，仅能显示文件名
 * @param url        可直接访问的只读 URL（如 /files/xxx）；storedName 为空时为 null
 * @param size       原文件字节数；未知时为 null
 */
public record AttachmentDto(String type, String filename, String storedName, String url, Long size) {
}
