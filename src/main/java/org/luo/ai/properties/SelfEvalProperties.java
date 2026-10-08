package org.luo.ai.properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 线上回答自评配置（{@code agent.self-eval.*}）。
 * <p>
 * <b>为什么默认关闭</b>：自评是一次额外的模型往返、按轮计费，默认打开等于给每条对话悄悄加一笔成本。
 * 故 {@code enabled} 默认 false，且打开时也是「按比例采样」而非全量。
 * 红线：<b>点踩触发的自评不受 {@code enabled} 约束</b>（{@code SelfEvalService#evaluateByFeedback}）—— 那是用户
 * 明确说「这条有问题」之后一次有目的的调用。关掉采样<b>不等于零成本</b>，这点必须写清。
 * 采样可重现：由 {@code traceId} 的哈希决定，同一轮无论重放多少次结论一致，排查「为什么没被评到」不靠运气复现。
 *
 * @param enabled           是否开启常规按比例自评（默认 false；点踩强制自评不看这一项）
 * @param sampleRate        采样比例 0.0~1.0（默认 0.1）；越界会被夹到区间内并记 WARN
 * @param minAnswerChars    回答短于该长度不自评（默认 80）—— 寒暄类回复没有可评内容，评了只给面板灌 5 分噪声
 * @param maxAnswerChars    送进自评提示词的回答截断长度（默认 2000）
 * @param lowScoreThreshold 「低分」阈值（默认 3）：面板把 {@code score <= 该值} 列为低分。只影响展示口径，不影响提示词里的打分锚点
 */
@ConfigurationProperties(prefix = "agent.self-eval")
public record SelfEvalProperties(Boolean enabled, Double sampleRate, Integer minAnswerChars,
                                 Integer maxAnswerChars, Integer lowScoreThreshold) {

    private static final Logger log = LoggerFactory.getLogger(SelfEvalProperties.class);

    /** 默认采样比例：约每 10 轮评 1 轮。 */
    public static final double DEFAULT_SAMPLE_RATE = 0.1;

    /** 默认最短可评回答长度（字符）。 */
    public static final int DEFAULT_MIN_ANSWER_CHARS = 80;

    /** 默认送评回答截断长度（字符）。 */
    public static final int DEFAULT_MAX_ANSWER_CHARS = 2000;

    /** 默认低分阈值。 */
    public static final int DEFAULT_LOW_SCORE_THRESHOLD = 3;

    public SelfEvalProperties {
        if (enabled == null) enabled = false;
        if (sampleRate == null || sampleRate.isNaN()) {
            sampleRate = DEFAULT_SAMPLE_RATE;
        } else if (sampleRate < 0 || sampleRate > 1) {
            // 夹到区间内但**不静默**：配错的值（如写成 10 想表达「10%」）如果不吭声，
            // 表现是「采样率调了没反应」，与「开关没生效」无法区分。
            double clamped = Math.max(0, Math.min(1, sampleRate));
            log.warn("self-eval.sample-rate={} 超出 [0,1]，已夹到 {}", sampleRate, clamped);
            sampleRate = clamped;
        }
        if (minAnswerChars == null || minAnswerChars < 0) minAnswerChars = DEFAULT_MIN_ANSWER_CHARS;
        if (maxAnswerChars == null || maxAnswerChars <= 0) maxAnswerChars = DEFAULT_MAX_ANSWER_CHARS;
        if (lowScoreThreshold == null || lowScoreThreshold < 1 || lowScoreThreshold > 5) {
            lowScoreThreshold = DEFAULT_LOW_SCORE_THRESHOLD;
        }
    }

    /** 常规按比例自评是否开启（点踩强制自评不受此约束）。 */
    public boolean sampleEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
