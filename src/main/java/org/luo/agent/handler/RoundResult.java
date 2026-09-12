package org.luo.agent.handler;

import org.luo.dto.KbCitation;

import java.util.List;

/**
 * 一轮对话的统一产出契约（各会话形态共用）。
 *
 * @param reply            本轮回复文本（clarified=true 时为追问文本）
 * @param clarified        true=本轮未给出正式回答、返回的是追问；false=正式回答
 * @param needSaveExchange 是否需要显式把「用户原话 → 最终回复」写入记忆：
 *                         规划多步执行为 true（执行期全程不写记忆）；
 *                         普通对话由记忆 Advisor 自动落库，恒为 false
 * @param citations        本轮回复引用到的 RAG 来源（与正文里的 [n] 角标对应）；
 *                         未走 RAG / 无命中为空表——调用方据此跳过落库与前端角标渲染
 */
public record RoundResult(String reply, boolean clarified, boolean needSaveExchange, List<KbCitation> citations) {

    /** 正式回答：无需显式补写记忆（普通路径由 Advisor 自动落库）。 */
    public static RoundResult answer(String reply) {
        return answer(reply, List.of());
    }

    /** 正式回答 + RAG 引用来源（供调用方落库 / 前端渲染角标）。 */
    public static RoundResult answer(String reply, List<KbCitation> citations) {
        return new RoundResult(reply, false, false, citations == null ? List.of() : citations);
    }

    /** 追问：本轮未给出正式回答，返回澄清问题（不涉及 RAG，引用恒为空）。 */
    public static RoundResult clarify(String question) {
        return new RoundResult(question, true, false, List.of());
    }

    /**
     * 本策略无法处理（回退信号）：调用方应改用普通对话策略处理本轮（见 {@code RoundHandler} 契约）。
     * 语义上等价于旧的「返回 null」，但显式命名让回退意图一目了然，避免后续新 handler 误用 null。
     */
    public static RoundResult fallback() {
        return new RoundResult(null, false, false, List.of());
    }

    /** 是否为回退信号（reply 为 null 即无法处理，调用方需回退普通对话策略）。 */
    public boolean isFallback() {
        return reply() == null;
    }
}
