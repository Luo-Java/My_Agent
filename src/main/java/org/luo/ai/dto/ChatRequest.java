package org.luo.ai.dto;

import java.util.List;

/**
 * 对话请求体。
 *
 * @param conversationId 会话 ID，用于多轮上下文；为空时由后端使用默认会话。
 * @param message        用户输入的消息内容。
 * @param planner        本轮是否按规划模式处理（true=动态规划器多智能体编排，false=普通对话）。
 *                       非空时覆盖会话自身的 planner 形态并写回会话；为空时跟随会话默认形态。
 * @param attachments    可选附件列表（图片由 VisionService 识别、文档由 DocumentParserService 解析，
 *                       统一产出 content 文本）。非空时由 ChatController 把 content 拼到 message 后注入上下文，
 *                       原始二进制不入会话记忆（保证存储与后续检索的轻量）。
 * @param branchGroupId  可选：本轮要落的对话分支组 ID（编辑重发 / 重新生成时由前端带 {@code /branch} 的结果）。
 *                       为空 = 普通追加一轮，不涉及版本。
 * @param branchVersion  可选：本轮要落的版本号，与 {@code branchGroupId} 成对出现。
 */
public record ChatRequest(String conversationId, String message, Boolean planner, List<ChatAttachment> attachments,
                          String branchGroupId, Integer branchVersion) {

    /** 兼容旧调用点（无 attachments、无分支）的便捷构造。 */
    public ChatRequest(String conversationId, String message, Boolean planner) {
        this(conversationId, message, planner, null, null, null);
    }

    /** 本轮的分支信息；非分叉轮返回 {@code null}（调用方据此整个跳过打标）。 */
    public TurnBranch branch() {
        return TurnBranch.of(branchGroupId, branchVersion);
    }
}
