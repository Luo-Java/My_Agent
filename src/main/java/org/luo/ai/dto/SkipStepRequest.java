package org.luo.ai.dto;

/**
 * 人工跳过某一步的请求体（规划模式执行中干预）。
 * <p>
 * 与 {@link ApproveStepRequest} 结构相同（会话 ID + 步骤下标）却独立成类：项目里 {@code *EnabledRequest}
 * 系列也是「同结构、各自命名」——调用方读到的类型名就是它要表达的动作，比复用同一个 record 更不容易看错。
 * <p>
 * 只改库（{@code task_step.status → SKIPPED}），<b>不触发执行</b>：由用户随后点「继续执行」走既有的断点续跑通路。
 * 代价要讲清楚：被跳过步骤的产出为 null，依赖它的下游步骤拿不到这段输入。
 *
 * @param conversationId 会话 ID；按该会话唯一 RUNNING 任务定位步骤（不传 taskId 是为了复用会话归属校验）
 * @param stepIndex      目标步骤下标（0 基，对应 {@code task_step.step_index}）
 */
public record SkipStepRequest(String conversationId, Integer stepIndex) {
}
