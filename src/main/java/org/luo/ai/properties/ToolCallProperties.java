package org.luo.ai.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 失控刹车配置（{@code agent.tool-call.*}）：给一轮对话内的模型调用装三道刹车，实现见
 * {@code BoundedToolCallingAdvisor} —— ① {@code maxIterations} 轮数硬顶（只兜真正的死循环，要容下
 * 「查表结构→查数据→画图」这类长链）；② {@code repeatThreshold} 连续同名同参判定原地打转；
 * ③ {@code roundBudgetTokens} 本轮累计 token 预算（跨步骤累加，口径即 {@code agent_trace.total_tokens}）。
 * <p>
 * 三道都走<b>软刹车</b>（不抛错，改为把「停止调工具、基于已有信息作答」追加进 system）。<b>为什么不是硬中断</b>：
 * 超预算时库里已躺着真实调用换来的中间结果，硬中断等于全丢并只回一条错误。代价是它<b>不是硬上限</b> ——
 * 刹车后还会再调一次模型产最终答案，真实用量可能高出上限一到两次。触发写 WARN（含 traceId）并播报，不落库。
 *
 * @param maxIterations     模型往返轮数上限（默认 10，覆盖教育数据分析的复杂查询链）
 * @param repeatThreshold   连续重复判定阈值（默认 3，连续 3 次同名同参即软刹车）
 * @param roundBudgetTokens 单轮累计 token 上限（默认 0=不启用，仅正数生效）
 */
@ConfigurationProperties(prefix = "agent.tool-call")
public record ToolCallProperties(int maxIterations, int repeatThreshold, long roundBudgetTokens) {

    public ToolCallProperties {
        if (maxIterations <= 0) maxIterations = 10;
        if (repeatThreshold <= 0) repeatThreshold = 3;
        if (roundBudgetTokens < 0) roundBudgetTokens = 0;
    }

    /** 单轮 token 预算是否生效（显式配置了正数）。关着时每次模型调用只多一次「0 值整数比较」，无额外成本。 */
    public boolean roundBudgetOn() {
        return roundBudgetTokens > 0;
    }
}
