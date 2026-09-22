package org.luo.ai.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工具循环控制配置（{@code agent.tool-call.*}）。
 * <p>
 * Spring AI 2.0 的 {@code ToolCallingAdvisor} 默认对「模型返回工具调用」无限循环（
 * {@code do...while(isToolCall)}），只靠模型自己停止。模型若反复调同一个工具就会陷入死循环、
 * token 无限累积、直到全局超时。本配置给这个循环装上两道刹车（见 {@code BoundedToolCallingAdvisor}）：
 * <ul>
 *   <li><b>轮数上限</b>（{@code maxIterations}）：模型往返轮数的硬顶，覆盖合理长链（查表结构→查数据→画图
 *       这类有依赖的链需要多轮），只兜底真正的死循环。</li>
 *   <li><b>连续重复检测</b>（{@code repeatThreshold}）：模型连续 N 次调用「同名工具且入参相同」判定原地打转，
 *       立即软刹车，避免「重复调同一个工具」拖到轮数上限才停。</li>
 * </ul>
 * 两道刹车都走「软刹车」：不抛错，而是把「已达上限，请基于已有信息作答」追加给模型，让它用已查到的
 * 中间结果给出结论，而非空手而归。
 *
 * @param maxIterations   模型往返轮数上限（默认 10，足够覆盖教育数据分析的复杂查询链）
 * @param repeatThreshold 连续重复调用判定的次数阈值（默认 3，连续 3 次同名同参即软刹车）
 */
@ConfigurationProperties(prefix = "agent.tool-call")
public record ToolCallProperties(int maxIterations, int repeatThreshold) {

    public ToolCallProperties {
        if (maxIterations <= 0) maxIterations = 10;
        if (repeatThreshold <= 0) repeatThreshold = 3;
    }
}
