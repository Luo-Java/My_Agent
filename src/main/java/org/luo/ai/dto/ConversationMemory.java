package org.luo.ai.dto;

import java.util.List;

/**
 * 会话长期记忆快照（供页面查看 / 编辑）。
 * <p>
 * 双层记忆此前是纯黑盒：压缩由 {@code MemoryMergeService} 异步写入，用户既看不到「它记住了什么」，
 * 也无法纠正记错的内容。本 DTO 把记忆摊开给用户，是唯一能解释「模型为什么突然提到某件旧事」的入口。
 * <p>
 * 另一半黑盒是「这一轮实际注入了什么」—— 那是窗口内的原文明细，与摘要（长期记忆）是两回事：
 * 摘要描述更早的、已经出窗的历史，窗口是仍在上下文里的近处对话。故一并给出 {@link #window}，
 * 让「它为什么记得 / 不记得」有完整的答案。
 * <p>
 * <b>事实的两处形态</b>：{@link #facts} 是逐条可管理的当前事实，{@link #coreFacts} 是旧版文本归档。
 * 注入时条目优先、归档只在「一条条目都没有」时兜底（见 {@code ConversationFactService}）——
 * 前端必须把这条规则显示出来，否则用户会看到「归档里有这段、模型却不提」，误判成模型忽略记忆。
 *
 * @param summary         滚动摘要（超窗历史的压缩结果）；null = 尚未产生
 * @param coreFacts       <b>旧版事实归档</b>：功能 E 之前承载「长期关键事实」的整段文本，现已由
 *                        {@link #facts} 逐条承载，自动流程不再改写它；null = 无归档。
 *                        保留它只为两件事：存量会话迁移时拆成条目的输入、用户留档。
 * @param summarizedCount 已被摘要覆盖的最旧消息条数（执行游标，只读展示 —— 手改它会让下次自动合并
 *                        从错误位置继续）
 * @param messageCount    当前会话消息总数（与 summarizedCount 相减可看出「还有多少条没进摘要」）
 * @param excludedCount   被用户标记「不参与记忆」的消息条数（这些既不在 window 里、也不会进摘要）
 * @param window          当前记忆窗口构成（将被注入 prompt 的历史，时间正序）；空表 = 没有历史可注入
 * @param facts           逐条长期事实（写入顺序）；空表 = 尚未整理出任何事实（此时注入侧回退读 coreFacts）
 */
public record ConversationMemory(String summary, String coreFacts, int summarizedCount, int messageCount,
                                 int excludedCount, List<InjectedMessage> window,
                                 List<ConversationFactDto> facts) {
}
