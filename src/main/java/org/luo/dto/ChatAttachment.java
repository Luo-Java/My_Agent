package org.luo.dto;

/**
 * 对话附件元数据（描述形式，非原始二进制）。多模态输入的最小契约：附件先经后端识别 / 解析转成纯文本
 * {@code content}，再由 {@code ChatController#extractAttachments} 抽为「当轮材料」文本块，经
 * {@code ChatComposer#withMaterial} 注入本轮 system prompt——既支持图片视觉理解与文档解析，又不破坏主对话
 * 模型的稳定性，且附件内容<b>仅当轮可见、不进会话记忆</b>（记忆 Advisor 只持久化 {@code .user()} 的纯提问）。
 * <p>
 * {@code storedName} / {@code size} 是原文件落盘信息：仅用于历史回看（缩略图 / 下载），同样不进 LLM 上下文。
 *
 * @param type       附件类型：{@code image}=图片（视觉模型识别）、{@code text}=已解析文档/文本、
 *                   {@code file}=无法解析的文件（content 为占位说明）
 * @param content    识别 / 解析后的纯文本；无法解析时为一句占位说明
 * @param storedName 原文件落盘后的存储名；未落盘 / 落盘失败时为 null
 */
public record ChatAttachment(String type, String content, String filename, String storedName, Long size) {

    /** 工厂：图片附件（content 为视觉模型 caption，可能含「识别失败」占位）。 */
    public static ChatAttachment image(String content, String filename) {
        return new ChatAttachment("image", content, filename, null, null);
    }

    /** 工厂：文档 / 文本附件（content 为解析出的正文）。 */
    public static ChatAttachment text(String content, String filename) {
        return new ChatAttachment("text", content, filename, null, null);
    }

    /** 工厂：无法解析的附件（content 为占位说明）。 */
    public static ChatAttachment file(String content, String filename) {
        return new ChatAttachment("file", content, filename, null, null);
    }

    /** 回填落盘信息（storedName=磁盘文件名，size=字节数），供历史记录展示缩略图 / 下载。 */
    public ChatAttachment withStorage(String storedName, Long size) {
        return new ChatAttachment(type, content, filename, storedName, size);
    }
}
