package org.luo.dto;

/**
 * 对话附件元数据（描述形式，非原始二进制）。
 * <p>
 * 多模态输入的最小契约：附件先经后端识别（图像走 qwen-vl-plus）转成文本描述 caption，
 * 再由 {@code ChatService} 把 caption 拼到用户消息后注入对话上下文。
 * 这样既支持视觉理解，又不破坏主对话模型（文本 qwen3.7-plus）的稳定性，
 * 也不引入原始二进制进会话记忆。
 *
 * @param type     附件类型（当前固定 {@code "image"}；预留 video/audio 扩展点）
 * @param caption  识别/转写后的文本描述（图片由 VisionService 产生）
 * @param filename 原始文件名（仅展示与排查用，不影响逻辑）
 */
public record ChatAttachment(String type, String caption, String filename) {

    /** 工厂：图片附件（caption 可能为「识别失败」之类的占位文本，调用方负责展示）。 */
    public static ChatAttachment image(String caption, String filename) {
        return new ChatAttachment("image", caption, filename);
    }
}