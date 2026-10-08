package org.luo.ai.dto;

import java.util.List;

/**
 * 会话长期记忆快照（供页面查看 / 编辑）。
 * <p>
 * 双层记忆此前是纯黑盒：压缩由 {@code MemoryMergeService} 异步写入，用户既看不到记住了什么、也无法纠正。
 * {@link #window} 是「这一轮实际注入了什么」（窗口内原文），与摘要（更早、已出窗的历史）是两回事，一并给出
 * 才能完整回答「它为什么记得 / 不记得」。
 * <b>事实的两处形态</b>：{@link #facts} 逐条可管理，{@link #coreFacts} 是旧版文本归档；注入时<b>条目优先</b>，
 * 归档只在「一条条目都没有」时兜底 —— 前端必须显示这条规则，否则用户会看到「归档里有、模型却不提」而误判。
 *
 * @param summary         滚动摘要（超窗历史的压缩结果）；null=尚未产生
 * @param coreFacts       旧版事实归档：自动流程不再改写，保留只为存量迁移的拆分输入与用户留档；null=无归档
 * @param summarizedCount 已被摘要覆盖的最旧消息条数（执行游标，只读展示 —— 手改会让下次自动合并从错误位置继续）
 * @param messageCount    当前会话消息总数（与 summarizedCount 相减可看出还有多少条没进摘要）
 * @param excludedCount   被标记「不参与记忆」的消息条数（既不在 window 里、也不进摘要）
 * @param window          当前记忆窗口构成（将被注入 prompt 的历史，时间正序）；空表=没有历史可注入
 * @param facts           逐条长期事实（写入顺序）；空表=尚未整理出任何事实（注入侧回退读 coreFacts）
 */
public record ConversationMemory(String summary, String coreFacts, int summarizedCount, int messageCount,
                                 int excludedCount, List<InjectedMessage> window,
                                 List<ConversationFactDto> facts) {
}
