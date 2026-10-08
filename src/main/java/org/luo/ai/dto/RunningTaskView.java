package org.luo.ai.dto;

import java.util.List;

/**
 * 「当前会话未完成任务」视图：断点续跑提示条（顶部 resume-bar）的数据源。
 * <p>
 * <b>为什么不直接返回 {@code Task} 实体</b>：提示条现在要回答「哪一步卡住了、能不能跳过它」，而 {@code task}
 * 表本身不含步骤状态 —— 返回实体只能给出「已完成 N/M 步」，用户看到进度却不知道卡在哪、也没有可点的动作。
 * 步骤明细必须一起给。
 * <p>
 * {@code agentName} 是<b>现查</b>而不是落库快照（与规划模板「只存 agentCode」同一取舍）：智能体改名后提示条
 * 立刻跟上，而 {@code agentCode} 才是稳定标识。
 *
 * @param id             任务 ID
 * @param conversationId 所属会话 ID
 * @param userGoal       用户原始目标
 * @param status         任务状态（本视图只会有 RUNNING，其余状态下 {@code findRunning} 查不到）
 * @param totalSteps     步骤总数
 * @param doneSteps      已完成步骤数（含被跳过的）
 * @param pauseRequested 是否已收到「暂停」请求（前端据此把按钮切成「已请求暂停…」并置灰）
 * @param steps          步骤明细（按 {@code step_index} 升序）
 */
public record RunningTaskView(String id, String conversationId, String userGoal, String status,
                              int totalSteps, int doneSteps, boolean pauseRequested, List<StepView> steps) {

    /**
     * 一个步骤的可读状态。
     *
     * @param index          步骤下标（0 基，与 {@code task_step.step_index} 同口径；展示时 +1）
     * @param agentCode      该步的智能体编码
     * @param agentName      该步的智能体名（现查；已被删除时回落成编码）
     * @param status         PENDING / RUNNING / DONE / SKIPPED / FAILED
     * @param error          失败原因或跳过原因（{@code error} 列，两者共用）
     * @param retryExhausted 是否「已失败且重试次数用尽」—— 这种步骤<b>再也不会被执行</b>，不跳过或重规划就会
     *                       把整个任务永久卡死（它的后继永远凑不齐前驱）。前端据此把它标成最该处理的步骤。
     */
    public record StepView(int index, String agentCode, String agentName, String status, String error,
                           boolean retryExhausted) {
    }
}
