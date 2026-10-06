package org.luo.ai.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 并行评审配置（{@code agent.review.*}）：会话级「⚖ 评审」开关打开时的候选与裁决行为。
 * <p>
 * 评审链路 = <b>选候选 → 并行作答 → 裁决综合</b>。三段的取舍：
 * <ul>
 *   <li><b>候选怎么定</b>：让 LLM 从智能体库里按「谁最适合回答这个问题」选，而不是固定名单 ——
 *       固定名单会让「问数学题也拉翻译官进来」，候选质量随问题类型漂移。会话若已显式绑定智能体，
 *       该智能体<b>固定占用一个候选位</b>（用户选了它，就该参与），其余由 LLM 补足；</li>
 *   <li><b>并行作答</b>：每个候选各自用自己的系统提示词回答同一问题，互不可见（避免互相抄）；
 *       复用规划步骤的线程池，N 个候选的等待压成 1 轮；</li>
 *   <li><b>裁决</b>：把各候选连署名一起交给裁决者，要求<b>综合</b>而非「选一个」—— 择优只在候选质量
 *       明显分层时才有意义，而现实中常见的是「A 的框架好、B 的细节对」，直接丢弃一份是浪费。</li>
 * </ul>
 * <p>
 * <b>不足 2 个候选时不静默降级</b>：库里只有一个智能体（或选角失败）时，链路明确退出并<b>播报原因</b>，
 * 由调用方回退普通对话 —— 而不是悄悄变成单模型回答让用户以为评审生效了。
 * <p>
 * <b>已知边界</b>：评审的 token 成本约等于 (候选数 + 1) 次普通对话，因此用量显著高于普通轮次；
 * 候选答案只走 SSE 展示，<b>不进会话记忆</b>（进记忆的只有综合后的结论），刷新后候选不可回看。
 *
 * @param candidates        候选数量（默认 3，含被绑定的智能体；小于下限 2 时回落到 3）
 * @param maxCandidates     候选数量硬上限（默认 5）：每个候选都是一次真实模型调用，不设上限等于放开成本
 * @param timeoutSeconds    单个候选作答的等待上限（秒，默认 90）：超时的候选按「弃权」处理并记 WARN，
 *                          不拖垮整轮 —— 但必须有候选成功，全部失败则本轮记为错误而非编一个答案
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
