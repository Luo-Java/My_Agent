package org.luo.ai.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 失控刹车配置（{@code agent.tool-call.*}）：给「一轮对话内的模型调用」装上三道刹车。
 * <p>
 * Spring AI 2.0 的 {@code ToolCallingAdvisor} 默认对「模型返回工具调用」无限循环（
 * {@code do...while(isToolCall)}），只靠模型自己停止。模型若反复调同一个工具就会陷入死循环、
 * token 无限累积、直到全局超时。三道刹车见 {@code BoundedToolCallingAdvisor}：
 * <ul>
 *   <li><b>轮数上限</b>（{@code maxIterations}）：模型往返轮数的硬顶，覆盖合理长链（查表结构→查数据→画图
 *       这类有依赖的链需要多轮），只兜底真正的死循环。</li>
 *   <li><b>连续重复检测</b>（{@code repeatThreshold}）：模型连续 N 次调用「同名工具且入参相同」判定原地打转，
 *       立即刹车，避免「重复调同一个工具」拖到轮数上限才停。</li>
 *   <li><b>单轮 token 预算</b>（{@code roundBudgetTokens}）：本轮累计 token（含工具循环内的每一次模型调用、
 *       规划模式下<b>跨步骤</b>累加，口径即 {@code agent_trace.total_tokens}）超过上限即刹车。</li>
 * </ul>
 * <p>
 * 三道都走「软刹车」：不抛错，而是把一条「停止调工具、基于已有信息作答」的指令追加进 system，
 * 让模型用已查到的中间结果给出结论，而非空手而归。
 * <p>
 * <b>为什么预算也做软刹车而不是硬中断</b>：一轮跑到超预算时，库里已经躺着若干次真实调用换来的中间结果
 * ——硬中断等于把它们全丢掉、只回一条错误，用户还得从头再问一遍（而且下一轮仍会撞上同一个上限）。
 * 软刹车让这一轮以「不完整但基于事实、且明确说明不完整」的方式收场，代价是<b>仍会多花一次模型调用的钱</b>。
 * 因此它<b>不是硬上限</b>：真实用量可能比 {@code roundBudgetTokens} 高出一到两次调用的量。
 * <p>
 * 刹车触发会写 WARN 日志（含 traceId，可用它检索整轮日志）并经进度通道播报，<b>不静默</b>；
 * 但为避免为一项可选治理能力新增落库列，本轮不写进 {@code agent_trace}。
 *
 * @param maxIterations      模型往返轮数上限（默认 10，足够覆盖教育数据分析的复杂查询链）
 * @param repeatThreshold    连续重复调用判定的次数阈值（默认 3，连续 3 次同名同参即软刹车）
 * @param roundBudgetTokens  单轮累计 token 上限（默认 0 = 不启用；仅正数生效）
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
