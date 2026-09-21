package org.luo.dto;

import java.util.List;

/**
 * 成本看板聚合视图（GET /api/cost/summary 的返回体）。
 * <p>
 * <b>全量口径</b>：{@code totalTokens} = {@code answerTokens}（agent_trace，正式回答 + 工具循环）
 * + {@code auxTokens}（llm_usage，路由 / 参数抽取 / 查询改写 / 视觉识别 / 记忆合并等裸调用）。
 * 与 TraceDto 里「回答成本」的口径区别：本看板把裸调用也纳入，故是「真正全量」的对话成本。
 *
 * @param answerTokens agent_trace 合计 token（回答本身）
 * @param auxTokens    llm_usage 合计 token（各类裸调用）
 * @param totalTokens  全量合计（answerTokens + auxTokens）
 * @param rounds       轮次数（agent_trace 记录数）
 * @param daily        按天趋势（升序）
 * @param byPurpose    按用途拆解（降序）
 */
public record CostSummary(long answerTokens, long auxTokens, long totalTokens, long rounds,
                          List<DailyCost> daily, List<PurposeCost> byPurpose) {

    /** 单日成本：日期 + 该日 token 合计（answer 与 aux 分开，便于前端叠图）。 */
    public record DailyCost(String day, long answerTokens, long auxTokens) {
    }

    /** 单用途成本：用途编码 + 中文标签 + token 合计 + 调用次数（仅裸调用 llm_usage 维度）。 */
    public record PurposeCost(String purpose, String label, long totalTokens, long calls) {
    }
}
