package org.luo.ai.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 并行评审配置（{@code agent.review.*}）：会话级「⚖ 评审」开关打开时的候选与裁决行为。
 * 候选由 LLM 按「谁最适合这个问题」从库里选而非固定名单（固定名单会让「问数学题也拉翻译官进来」）；
 * 会话已显式绑定的智能体<b>固定占一个候选位</b>。裁决要求<b>综合</b>而非择优 —— 现实中常见「A 的框架好、
 * B 的细节对」，丢弃一份是浪费。
 * <p>
 * 红线：<b>不足 2 个候选时不静默降级</b>，明确退出并播报原因、由调用方回退普通对话，绝不让用户以为评审生效了。
 * 成本约 (候选数 + 1) 次普通对话；候选只走 SSE、<b>不进记忆也不落库</b>，刷新后不可回看。
 *
 * @param candidates     候选数量（默认 3，含被绑定的智能体；小于下限 2 时回落到 3）
 * @param maxCandidates  候选数量硬上限（默认 5）：每个候选都是一次真实模型调用，不设上限等于放开成本
 * @param timeoutSeconds 单个候选等待上限（秒，默认 90）：超时按弃权处理并记 WARN；但必须至少一个成功
 */
@ConfigurationProperties(prefix = "agent.review")
public record ReviewProperties(Integer candidates, Integer maxCandidates, Integer timeoutSeconds) {

    /** 候选数量下限：少于 2 个就失去了「评审」的意义。 */
    public static final int MIN_CANDIDATES = 2;

    /** 默认候选数量。 */
    public static final int DEFAULT_CANDIDATES = 3;

    /** 候选数量默认上限。 */
    public static final int DEFAULT_MAX_CANDIDATES = 5;

    /** 单候选默认等待上限（秒）。 */
    public static final int DEFAULT_TIMEOUT_SECONDS = 90;

    public ReviewProperties {
        if (maxCandidates == null || maxCandidates < MIN_CANDIDATES) maxCandidates = DEFAULT_MAX_CANDIDATES;
        if (candidates == null || candidates < MIN_CANDIDATES) candidates = DEFAULT_CANDIDATES;
        if (candidates > maxCandidates) candidates = maxCandidates;
        if (timeoutSeconds == null || timeoutSeconds <= 0) timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    }

    /** 候选数量（已归一化到 [2, maxCandidates]）。 */
    public int candidateCount() {
        return candidates == null ? DEFAULT_CANDIDATES : candidates;
    }

    /** 单候选等待上限（秒）。 */
    public int timeout() {
        return timeoutSeconds == null ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
    }
}
