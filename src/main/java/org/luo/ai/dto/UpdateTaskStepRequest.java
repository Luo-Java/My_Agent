package org.luo.ai.dto;

import java.util.List;

/**
 * 就地编辑「待确认计划」中某一步的请求体（规划模式「先看计划」）。
 * <p>
 * 只能改尚未执行的步骤（{@code task_step.status = PENDING}）：智能体、指令、依赖前驱、审批标记四列。
 * <b>步骤的增 / 删 / 换序不在此接口范围</b>——那会打乱 {@code step_index} 的连续性与其它步骤已落库的依赖下标，
 * 该场景由「局部重规划」（{@code POST /api/chat/task/replan}）承担。
 *
 * @param conversationId 会话 ID；按该会话唯一 RUNNING 任务定位待编辑步骤（不传 taskId 是为了复用会话归属校验）
 * @param stepIndex      目标步骤下标（0 基，对应 {@code task_step.step_index}）
 * @param agentCode      新的智能体编码；非空必填
 * @param instruction    新的步骤指令（可空，表示不给该步额外指令）
 * @param dependsOn      新的依赖前序下标列表；只能指向比 {@code stepIndex} 更早的步骤，null / 空 = 无依赖
 * @param approvalRequired 是否「执行前需审批」；<b>null = 不改该列</b>（只改智能体/指令/依赖的调用方不必关心它）
 */
public record UpdateTaskStepRequest(String conversationId, Integer stepIndex, String agentCode,
                                    String instruction, List<Integer> dependsOn, Boolean approvalRequired) {
}
