package org.luo.ai.dto;

/**
 * 把某个任务的步骤骨架存为模板的请求体。
 * <p>
 * 用 {@code taskId} 而不是 {@code conversationId} 定位来源，原因具体：任务跑完之后状态多半已是
 * {@code DONE}/{@code FAILED}，而 {@code TaskService.findRunning} 只认 {@code RUNNING} ——
 * 按会话定位会取不到「刚刚跑完、正想存成模板」的那次计划，恰好是唯一值得存的时机。
 * 归属校验因此改为「先按 taskId 取任务，再用它的 conversationId 走会话归属校验」，复用同一条规则。
 *
 * @param taskId      来源任务 ID（计划卡片上的 taskId）
 * @param name        模板名称；非空必填，≤ 100 字符（超长由服务层截断而非报错）
 * @param description 备注（可空，≤ 500 字符）
 */
public record SaveTaskTemplateRequest(String taskId, String name, String description) {
}
