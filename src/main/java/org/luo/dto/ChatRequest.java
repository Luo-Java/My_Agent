package org.luo.dto;

import java.util.List;

/**
 * 对话请求体。
 *
 * @param conversationId 会话 ID，用于多轮上下文；为空时由后端使用默认会话。
 * @param message        用户输入的消息内容。
 * @param planner        本轮是否按规划模式处理（true=动态规划器多智能体编排，false=普通对话）。
 *                       非空时覆盖会话自身的 planner 形态并写回会话；为空时跟随会话默认形态。
 * @param attachments    可选附件列表（当前类型为 {@code image}，caption 由后端 VisionService 产生）。
 *                       非空时由 ChatService 把 caption 拼到 message 后注入上下文，
 *                       原始二进制不入会话记忆（保证存储与后续检索的轻量）。
 */
public record ChatRequest(String conversationId, String message, Boolean planner, List<ChatAttachment> attachments) {

    /** 兼容旧调用点（无 attachments）的便捷构造。 */
    public ChatRequest(String conversationId, String message, Boolean planner) {
        this(conversationId, message, planner, null);
    }
}