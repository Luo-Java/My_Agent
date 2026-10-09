package org.luo.ai.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 阈值告警与周期报告配置（{@code agent.alert.*}）。
 * <p>
 * 把可观测面板「要人主动去看」的指标变成「越线主动推」，两条通路共用一个调度基础设施：
 * <ul>
 *   <li><b>阈值告警</b>（{@link #enabled}）：按 {@code windowDays} 聚合，逐项与阈值比对，越线即落一条
 *       {@code ALERT} 通知。目标人群是 ADMIN（全站口径的指标给普通用户看只会造成困惑）。</li>
 *   <li><b>周期报告</b>（{@link #reportEnabled}）：按 {@code report-cron} 生成一份运行摘要通知。</li>
 * </ul>
 * <b>为何要有 {@link #minSample}</b>：阈值判定最怕小样本噪声 —— 一天只有 3 轮、其中 1 轮失败就是 66.7%
 * 成功率，按阈值判会天天报警，报警一多就没人看了（等于把告警变成噪声）。样本不足时<b>明确记 debug 日志</b>
 * 而不是静默跳过，便于排查「为什么没告警」。
 * <p>
 * <b>为何要有 {@link #cooldownMinutes}</b>：指标是<b>持续状态</b>（成功率跌破后不会自己回去），
 * 每轮检查都推一次会把通知面板刷满，反而盖住真正新发生的事。冷却期内同一项只报一次。
 * 冷却表存内存，重启即清空 —— 宁可重启后多报一次，也不要持久化后因写失败永久静默。
 * <p>
 * 轮询间隔与报告 cron 直接用占位符写在 {@code @Scheduled} 上（{@code agent.alert.check-interval-ms} /
 * {@code agent.alert.report-cron}），不入本类：它们是调度器读的，本类读不到也管不着，放进来只会多一份
 * 永不生效的「配置」。
 *
 * @param enabled          告警总开关（默认 true）
 * @param windowDays       聚合窗口天数（默认 1）：告警关心「现在怎么样」，不是「这周平均怎么样」
 * @param minSample        最小样本轮次（默认 10）：低于它不做阈值判定（避免小样本噪声）
 * @param minSuccessRate   成功率下限（0~1，默认 0.90）
 * @param maxAvgElapsedMs  平均耗时上限（毫秒，默认 15000）
 * @param maxLowScoreCount 低分自评轮次上限（默认 5）；低分阈值复用 {@code agent.self-eval.low-score-threshold}
 * @param cooldownMinutes  同一告警项的冷却分钟数（默认 60）
 * @param reportEnabled    周期报告开关（默认 true）
 * @param reportDays       报告统计窗口天数（默认 1）
 */
@ConfigurationProperties(prefix = "agent.alert")
public record AlertProperties(Boolean enabled, Integer windowDays, Integer minSample, Double minSuccessRate,
                              Long maxAvgElapsedMs, Integer maxLowScoreCount, Integer cooldownMinutes,
                              Boolean reportEnabled, Integer reportDays) {

    /** 默认最小样本轮次。 */
    public static final int DEFAULT_MIN_SAMPLE = 10;

    public AlertProperties {
        if (enabled == null) enabled = true;
        if (windowDays == null || windowDays <= 0) windowDays = 1;
        if (minSample == null || minSample < 0) minSample = DEFAULT_MIN_SAMPLE;
        if (minSuccessRate == null || minSuccessRate < 0 || minSuccessRate > 1) minSuccessRate = 0.90;
        if (maxAvgElapsedMs == null || maxAvgElapsedMs <= 0) maxAvgElapsedMs = 15_000L;
        if (maxLowScoreCount == null || maxLowScoreCount < 0) maxLowScoreCount = 5;
        if (cooldownMinutes == null || cooldownMinutes < 0) cooldownMinutes = 60;
        if (reportEnabled == null) reportEnabled = true;
        if (reportDays == null || reportDays <= 0) reportDays = 1;
    }

    /** 告警是否启用。 */
    public boolean enabledOn() {
        return Boolean.TRUE.equals(enabled);
    }

    /** 周期报告是否启用。 */
    public boolean reportOn() {
        return Boolean.TRUE.equals(reportEnabled);
    }
}
