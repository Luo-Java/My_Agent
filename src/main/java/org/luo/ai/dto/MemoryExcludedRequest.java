package org.luo.ai.dto;

/**
 * 「不参与记忆」开关的请求体（{@code PUT /api/chat/message/{messageId}/memory-excluded}）。
 * <p>
 * 字段用包装类型 {@link Boolean} 而非 {@code boolean}：缺字段与显式 {@code false} 是两件事 ——
 * 前者是请求写错了，应报 400；后者是用户的正常操作（把标记取消）。用基本类型会让「漏传」静默变成
 * 「取消标记」，而用户以为自己刚把它排出了记忆。
 *
 * @param excluded {@code true}=该条既不进记忆窗口、也不进滚动摘要；{@code false}=恢复正常参与
 */
public record MemoryExcludedRequest(Boolean excluded) {
}
