package org.luo.ai.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 规划模式「步骤间产物传递」配置（{@code agent.planner.*}）。
 * <p>
 * 动态规划靠「前驱产出拼进下一步输入」串联，改造前这条拼接<b>没有任何上限</b>：只要上游产出长文，输入就随
 * 「步骤数 × 单步产出长度」无界增长、最终击穿上下文 —— 且是<b>静默失败</b>（模型自行丢弃中间内容，不抛错、日志无痕）。
 * 配额 = {@code min(upstreamMaxChars, upstreamTotalChars / 前驱个数)}：前者防单个前驱过长，后者防前驱个数过多。
 * <b>不支持关闭</b>（非正数回落默认值）—— 无界增长是缺陷不是选项。<b>截断必须可见</b>：保留前段 + 显式省略标注 + WARN。
 * 本配置只约束「注入下一步的输入」，不影响 {@code task_step.output} 与最终回复。
 *
 * @param upstreamMaxChars   单个前驱产出注入下一步的字符上限（默认 4000，与 {@code agent.memory.max-message-chars} 同档）
 * @param upstreamTotalChars 全部前驱产出合计注入的字符上限（默认 12000，按前驱个数均分）
 * @param isolateMiddleSteps 中间步骤是否隔离长期记忆：true=不注入 core_facts / summary（默认）
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
