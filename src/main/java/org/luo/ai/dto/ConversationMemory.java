package org.luo.ai.dto;

/**
 * 会话长期记忆快照（供页面查看 / 编辑）。
 * <p>
 * 双层记忆此前是纯黑盒：压缩由 {@code MemoryMergeService} 异步写入，用户既看不到「它记住了什么」，
 * 也无法纠正记错的内容。本 DTO 把三列摊开给用户，是唯一能解释「模型为什么突然提到某件旧事」的入口。
 *
 * @param summary         滚动摘要（超窗历史的压缩结果）；null = 尚未产生
 * @param coreFacts       核心事实（姓名 / 身份 / 偏好 / 待办等长期关键事实）；null = 尚未产生
 * @param summarizedCount 已被摘要覆盖的最旧消息条数（执行游标，只读展示 —— 手改它会让下次自动合并
 *                        从错误位置继续）
 * @param messageCount    当前会话消息总数（与 summarizedCount 相减可看出「还有多少条没进摘要」）
 */
public record ConversationMemory(String summary, String coreFacts, int summarizedCount, int messageCount) {
}
