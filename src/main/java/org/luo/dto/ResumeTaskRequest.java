package org.luo.dto;

/**
 * 续跑未完成任务请求体。
 *
 * @param conversationId 会话 ID；按该会话唯一 RUNNING 任务定位续跑目标。
 */
public record ResumeTaskRequest(String conversationId) {
}
