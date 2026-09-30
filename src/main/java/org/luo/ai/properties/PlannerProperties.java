package org.luo.ai.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 规划模式「步骤间产物传递」配置（{@code agent.planner.*}）。
 * <p>
 * 动态规划的步骤靠「前驱产出拼进下一步输入」串联。改造前这条拼接<b>没有任何上限</b>：第 N 步会把
 * 全部前驱产出的全文原样带进自己的 user 消息。只要上游产出长文（分析报告、数据表），拼出的输入就随
 * 「步骤数 × 单步产出长度」<b>无界增长</b>，最终击穿模型上下文——且是<b>静默失败</b>：模型自行丢弃
 * 中间内容，不抛错、日志无痕，表现为「答得莫名其妙」而查不出原因。本类给这条链路装上上界，做法与
 * {@link MemoryProperties} 对会话窗口的处理一致。
 * <p>
 * <b>配额计算</b>：单个前驱实际能带进下一步的字符数 =
 * {@code min(upstreamMaxChars, upstreamTotalChars / 前驱个数)}。
 * 前者防「单个前驱特别长」，后者防「前驱个数特别多」，两者同时约束才能保证输入长度有上界。
 * <p>
 * <b>不支持关闭</b>：两个参数都必须为正（非正数一律回落到默认值）。无界增长是本链路的缺陷而非可选项，
 * 留一个「置 0 即恢复原行为」的开关只会让缺陷悄悄回来。
 * <p>
 * <b>截断必须可见</b>：配额不足时保留前段并追加明确的省略标注（让模型知道此处不完整、不要据被省略的
 * 部分下结论），同时落 WARN。不允许静默截断——那会把「模型答偏」变成一个查不出原因的谜。
 * <p>
 * 本配置只约束<b>注入下一步的输入</b>，不影响步骤产出的落库（{@code task_step.output}）与最终回复：
 * 用户看到的始终是完整产出。
 *
 * @param upstreamMaxChars   单个前驱产出注入下一步的字符上限（默认 4000，与 {@code agent.memory.max-message-chars} 同档）
 * @param upstreamTotalChars 全部前驱产出合计注入的字符上限（默认 12000，按前驱个数均分）
 * @param isolateMiddleSteps 中间步骤（既非首层也非末步）是否隔离长期记忆：true=不注入 core_facts / summary（默认），
 *                           false=与首末步一样注入
 */
@ConfigurationProperties(prefix = "agent.planner")
public record PlannerProperties(Integer upstreamMaxChars, Integer upstreamTotalChars, Boolean isolateMiddleSteps) {

    /** 单个前驱产出的默认上限（字符）：与单条历史消息同档，中间产物不该比对话历史占更多预算。 */
    public static final int DEFAULT_UPSTREAM_MAX_CHARS = 4000;

    /** 前驱产出合计的默认上限（字符）：留出 3 个满额前驱的空间，再多就按个数均分递减。 */
    public static final int DEFAULT_UPSTREAM_TOTAL_CHARS = 12000;

    public PlannerProperties {
        if (upstreamMaxChars == null || upstreamMaxChars <= 0) upstreamMaxChars = DEFAULT_UPSTREAM_MAX_CHARS;
        if (upstreamTotalChars == null || upstreamTotalChars <= 0) upstreamTotalChars = DEFAULT_UPSTREAM_TOTAL_CHARS;
        if (isolateMiddleSteps == null) isolateMiddleSteps = true;
    }

    /**
     * 计算「单个前驱产出」实际可注入的字符配额：先按前驱个数把总预算均分，再夹一次单条上限。
     * 前驱数 &le; 0 时返回 0（调用方不会走到）；均分结果至少为 1，避免前驱数极多时配额算成 0、
     * 把上游产出整段丢光。
     */
    public int perUpstreamChars(int upstreamCount) {
        if (upstreamCount <= 0) return 0;
        return Math.max(1, Math.min(upstreamMaxChars, upstreamTotalChars / upstreamCount));
    }

    /** 中间步骤是否隔离长期记忆（默认 true）。 */
    public boolean isolateMiddleStepsOn() {
        return Boolean.TRUE.equals(isolateMiddleSteps);
    }
}
