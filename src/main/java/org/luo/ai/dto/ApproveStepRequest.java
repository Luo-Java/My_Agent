package org.luo.ai.dto;

/**
 * 批准「待审批步骤」的请求体（规划模式步骤审批点）。
 * <p>
 * 批准只写标记（{@code task_step.approved = 1}），<b>不触发执行</b>：前端接着调
 * {@code POST /api/chat/task/resume} 走断点续跑继续跑。拆成两步是为了让执行逻辑仍然只有一处。
 *
 * @param conversationId 会话 ID；按该会话唯一 RUNNING 任务定位步骤（不传 taskId 是为了复用会话归属校验）
 * @param stepIndex      目标步骤下标（0 基，对应 {@code task_step.step_index}）
 */
public record ApproveStepRequest(String conversationId, Integer stepIndex) {
}
