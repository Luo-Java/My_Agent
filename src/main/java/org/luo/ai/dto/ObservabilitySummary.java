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
 * <b>仅 ADMIN 可访问</b>：跨会话全站聚合，天然运营视角（同成本看板）。
 *
 * @param overview      总览指标
 * @param daily         按天趋势（升序）
 * @param byMode        按形态拆解（agent / planner）
 * @param byRouteSource 按处理方来源拆解
 * @param byAgent       按智能体拆解（轮次降序）
 * @param slowest       最慢的几轮（耗时降序，便于定位瓶颈）
 */
public record ObservabilitySummary(Overview overview,
                                   List<DailyBucket> daily,
                                   List<ModeBucket> byMode,
                                   List<RouteBucket> byRouteSource,
                                   List<AgentBucket> byAgent,
                                   List<SlowRound> slowest) {

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
}
