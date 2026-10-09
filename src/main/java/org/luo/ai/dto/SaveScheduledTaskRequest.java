package org.luo.ai.dto;

/**
 * 定时任务保存请求体（新建与修改共用；带 {@code id} 即修改）。
 *
 * @param id       任务 ID（为空 = 新建）
 * @param name     任务名称（同时用作首次执行时创建会话的标题）
 * @param cron     触发周期（Spring {@code CronExpression} 六段式：秒 分 时 日 月 周）
 * @param agentId  绑定智能体 ID（可空 = 走智能路由）
 * @param prompt   到点时发给对话的提示词
 * @param enabled  是否启用（可空 = 新建时默认 true）
 * @param notifyOn 执行完成后是否发通知（可空 = 默认 true）
 */
public record SaveScheduledTaskRequest(Long id, String name, String cron, Long agentId, String prompt,
                                       Boolean enabled, Boolean notifyOn) {
}
