package org.luo.service.handler;

/**
 * 一轮对话的统一产出契约（各会话形态共用）。
 *
 * @param reply            本轮回复文本（clarified=true 时为追问文本）
 * @param clarified        true=本轮未给出正式回答、返回的是追问；false=正式回答
 * @param needSaveExchange 是否需要显式把「用户原话 → 最终回复」写入记忆：
 *                         规划多步执行为 true（执行期全程不写记忆）；
 *                         普通对话由记忆 Advisor 自动落库，恒为 false
 */
public record RoundResult(String reply, boolean clarified, boolean needSaveExchange) {

    /** 正式回答：无需显式补写记忆（普通路径由 Advisor 自动落库）。 */
    public static RoundResult answer(String reply) {
        return new RoundResult(reply, false, false);
    }

    /** 追问：本轮未给出正式回答，返回澄清问题。 */
    public static RoundResult clarify(String question) {
        return new RoundResult(question, true, false);
    }

    /**
     * 本策略无法处理（回退信号）：调用方应改用普通对话策略处理本轮（见 {@code RoundHandler} 契约）。
     * 语义上等价于旧的「返回 null」，但显式命名让回退意图一目了然，避免后续新 handler 误用 null。
     */
    public static RoundResult fallback() {
        return new RoundResult(null, false, false);
    }

    /** 是否为回退信号（reply 为 null 即无法处理，调用方需回退普通对话策略）。 */
    public boolean isFallback() {
        return reply() == null;
    }
}
