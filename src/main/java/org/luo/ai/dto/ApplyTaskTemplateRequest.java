package org.luo.ai.dto;

/**
 * 套用规划模板的请求体：按模板的步骤骨架在当前会话落库成一个新任务，<b>不自动执行</b>
 * （与「先看计划」同一节奏 —— 先生成、用户可逐条改，再点「执行计划」走续跑通路）。
 *
 * @param conversationId 目标会话 ID（归属校验用）
 * @param templateId     模板 ID；不存在或非本人一律 404（与「不存在」不可区分，防 ID 探测）
 * @param goal           本次目标；<b>非空必填</b> —— 模板只描述「怎么排」（哪个智能体、什么指令、依赖关系），
 *                       不含「做什么」；而执行侧把无依赖步骤的输入取为任务目标
 *                       （{@code PlannerRoundHandler#buildStepInput} 的 {@code firstInput}），
 *                       目标为空会让第一步拿不到任何输入。
 */
public record ApplyTaskTemplateRequest(String conversationId, Long templateId, String goal) {
}
