package org.luo.dto;

/**
 * 对话请求体。
 *
 * @param conversationId 会话 ID，用于多轮上下文；为空时由后端使用默认会话。
 * @param message        用户输入的消息内容。
 * @param planner        本轮是否按规划模式处理（true=动态规划器多智能体编排，false=普通对话）。
 *                       非空时覆盖会话自身的 planner 形态并写回会话；为空时跟随会话默认形态。
 */
public record ChatRequest(String conversationId, String message, Boolean planner) {
}
