package org.luo.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 可观测面板聚合视图（GET /api/observability/summary 的返回体）。
 * <p>
 * 聚合 {@code agent_trace}（一轮对话的链路留痕），回答「最近 N 天整体运行得怎么样」：
 * 多少轮、成功率、平均耗时、token 花费、按天/按模式/按路由来源/按智能体拆解、以及最慢的几轮。
 * 与 {@link CostSummary} 的分工：成本看板只讲「花了多少 token」；本面板讲「运行质量」——
 * 耗时、失败率、瓶颈在哪里。
 * <p>
 * <b>回答质量那一块单独占两个分量</b>（{@link #selfEval} 与 {@link #lowRounds}），没有塞进
 * {@link Overview}：自评只覆盖被抽检 / 被点踩的那部分轮次，把它混进「全站总览」会让成功率下面挂一个
 * 分母不同的百分比，看着像同一件事。分开摆，口径各自说清。
 * <p>
 * <b>仅 ADMIN 可访问</b>：跨会话全站聚合，天然运营视角（同成本看板）。
 *
 * @param overview      总览指标
 * @param daily         按天趋势（升序）
 * @param byMode        按形态拆解（agent / planner）
 * @param byRouteSource 按处理方来源拆解
 * @param byAgent       按智能体拆解（轮次降序）
 * @param slowest       最慢的几轮（耗时降序，便于定位瓶颈）
 * @param selfEval      回答自评覆盖与分数（窗口内全站）；未自评的轮次单独计数，不与低分混算
 * @param lowScoreThreshold 低分阈值（{@code agent.self-eval.low-score-threshold}）—— 面板要显示「≤ N 分」，
 *                      不能在前端写死：阈值改了后端判断，界面不改就会嘴上说 3 分、实际按别的分算
 * @param lowRounds     低分轮次（分数升序，同分取最近）；明细由追踪详情接口给
 */
public record ObservabilitySummary(Overview overview,
                                   List<DailyBucket> daily,
                                   List<ModeBucket> byMode,
                                   List<RouteBucket> byRouteSource,
                                   List<AgentBucket> byAgent,
                                   List<SlowRound> slowest,
                                   SelfEvalSummary selfEval,
                                   int lowScoreThreshold,
                                   List<LowRound> lowRounds) {

    /**
     * 总览：窗口内整体运行质量。
     * <p>
     * <b>{@code successRate} 刻意不是构造器分量</b>，而是下面的派生方法，两个原因：
     * <ol>
     *   <li><b>MyBatis 硬约束</b>：本 DTO 直接充当 {@code aggregateOverview} 的结果投影，而 MyBatis 的
     *       record 构造器自动映射（本项目未开 {@code argNameBasedConstructorAutoMapping}，按列序映射）
     *       要求<b>构造器参数个数 = 结果集列数</b>，多一个参数就会在取数时抛
     *       {@code ExecutorException: The constructor takes '6' arguments, but there are only '5' columns}。
     *       让 SQL 多查一列纯属为了凑数，只会把「同一事实」表达两遍。</li>
     *   <li><b>单一事实来源</b>：成功率是 rounds/errors 的纯函数，派生出来就不可能与之漂移。</li>
     * </ol>
     * JSON 输出仍是 {@code successRate} 字段（{@code @JsonProperty} 显式命名，不依赖 getter 命名约定）。
     */
    public record Overview(long rounds, long errors, long avgElapsedMs, long avgTokens, long totalTokens) {

        /** 成功率（%，两位小数）：由 rounds/errors 派生；无数据（rounds=0）视作 100% —— 没有轮次就谈不上失败。 */
        @JsonProperty("successRate")
        public double successRate() {
            return rounds == 0 ? 100.0 : Math.round((1.0 - (double) errors / rounds) * 10000.0) / 100.0;
        }
    }

    /** 单日桶：日期 + 轮次 + 失败数 + 平均耗时 + 平均 token。 */
    public record DailyBucket(String day, long rounds, long errors, long avgElapsedMs, long avgTokens) {
    }

    /** 单形态桶：agent / planner。 */
    public record ModeBucket(String mode, long rounds, long errors, long avgElapsedMs, long avgTokens) {
    }

    /** 单来源桶：BOUND / ROUTE / NONE / PLAN / REVIEW。 */
    public record RouteBucket(String routeSource, long rounds, long errors, long avgElapsedMs) {
    }

    /** 单智能体桶：agent_code（null 归为「通用助手」）。 */
    public record AgentBucket(String agentCode, long rounds, long errors, long avgElapsedMs, long avgTokens) {
    }

    /** 慢轮明细：定位「哪一轮最慢、在哪个会话、什么形态、走了哪个智能体」。 */
    public record SlowRound(String traceId, String conversationId, String mode,
                            String routeSource, String agentCode, long elapsedMs,
                            long totalTokens, String status, String userMessage) {
    }

    /**
     * 回答自评的覆盖与分数（窗口内全站）。
     * <p>
     * <b>{@code unevaluated} 必须与 {@code lowScore} 分开</b>：前者是「没被评到」（采样未命中 / 回答过短 /
     * 自评调用失败 / 刚跑完还没评完），后者是「评了低分」。混成一个数会直接把「覆盖不足」读成「质量差」——
     * 而这两者的处置方式完全相反（一个要调采样率，一个要看提示词或模型）。
     * <p>
     * <b>覆盖率与低分率都是派生方法</b>（不占构造器分量）：本 record 直接充当 MyBatis 的结果投影，
     * 构造器参数个数必须等于 SELECT 列数（见 {@link Overview} 的说明）。
     *
     * @param evaluated   已自评轮次数（{@code self_eval_score IS NOT NULL}）
     * @param unevaluated 未自评轮次数（{@code self_eval_score IS NULL}）
     * @param lowScore    低分轮次数（分数 ≤ 阈值；阈值来自 {@code agent.self-eval.low-score-threshold}）
     * @param avgScore    已自评轮次的平均分（保留两位；一次都没评时为 0）
     */
    public record SelfEvalSummary(long evaluated, long unevaluated, long lowScore, double avgScore) {

        /** 自评覆盖率（%，两位小数）：已自评 / 窗口内全部轮次。 */
        @JsonProperty("coverage")
        public double coverage() {
            long total = evaluated + unevaluated;
            return total == 0 ? 0.0 : Math.round((double) evaluated / total * 10000.0) / 100.0;
        }

        /**
         * 低分率（%，两位小数）：低分 / <b>已自评</b>。
         * 分母刻意用已自评而不是全部轮次 —— 否则调低采样率会「让质量变好」，那是指标自欺。
         */
        @JsonProperty("lowRate")
        public double lowRate() {
            return evaluated == 0 ? 0.0 : Math.round((double) lowScore / evaluated * 10000.0) / 100.0;
        }
    }

    /**
     * 低分轮次明细：只回定位信息（分数 + 会话 + 形态 + 用户消息前 80 字）。
     * <p>
     * <b>刻意不带自评明细 JSON</b>：默认列表最多几十行，每行塞一份明细等于把几十份 JSON 一起拉回来，而它们
     * 只在用户点开某一轮时才需要 —— 明细走 {@code GET /api/trace/{traceId}} 的 {@code selfEval} 字段。
     */
    public record LowRound(String traceId, String conversationId, String mode, String routeSource,
                           String agentCode, long elapsedMs, long selfEvalScore, String userMessage) {
    }
}
