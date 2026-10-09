package org.luo.ai.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 定时任务调度配置（{@code agent.schedule.*}）。
 * <p>
 * <b>为什么是「轮询表」而不是内存定时器</b>：任务的增删改在 HTTP 线程上发生，而触发在调度线程上；
 * 用 {@code TaskScheduler} 为每个任务注册 {@code CronTrigger} 意味着「改一次 cron 就要重排一次内存里的
 * 触发器」，且重启后要靠一堆补偿逻辑复原。改为「每分钟扫一遍库，取 {@code next_run_at <= now} 的任务」：
 * 新增/修改/删除天然生效（下一轮就带上或不再带上），重启后自动恢复，多实例部署下也不会漏（代价是可能
 * 重复触发，故执行前用状态位抢占，见 {@code ScheduledTaskService}）。
 * <p>
 * {@code poll-interval-ms} 决定「最坏晚多久执行」：轮询周期越长，cron 到点与实际执行的偏差越大。
 * 默认 30 秒，对「每天跑一次」这类场景绰绰有余，且不构成明显空转。
 *
 * @param enabled        总开关（默认 true）；关闭后调度器不再扫描，已有任务定义保留
 * @param pollIntervalMs 轮询间隔（毫秒，默认 30000）
 * @param maxPerTick     单轮最多执行几个到期任务（默认 3）：防止同一时刻堆积过多把调度线程与模型额度打满
 * @param maxResultChars 结果摘要落库与通知正文的截断长度（默认 500）
 */
@ConfigurationProperties(prefix = "agent.schedule")
public record ScheduleProperties(Boolean enabled, Long pollIntervalMs, Integer maxPerTick, Integer maxResultChars) {

    /** 默认轮询间隔（毫秒）。 */
    public static final long DEFAULT_POLL_INTERVAL_MS = 30_000L;

    /** 单轮默认执行上限。 */
    public static final int DEFAULT_MAX_PER_TICK = 3;

    /** 默认结果摘要截断长度。 */
    public static final int DEFAULT_MAX_RESULT_CHARS = 500;

    public ScheduleProperties {
        if (enabled == null) enabled = true;
        if (pollIntervalMs == null || pollIntervalMs < 5_000L) pollIntervalMs = DEFAULT_POLL_INTERVAL_MS;
        if (maxPerTick == null || maxPerTick <= 0) maxPerTick = DEFAULT_MAX_PER_TICK;
        if (maxResultChars == null || maxResultChars <= 0) maxResultChars = DEFAULT_MAX_RESULT_CHARS;
    }

    /** 调度器是否生效。 */
    public boolean enabledOn() {
        return Boolean.TRUE.equals(enabled);
    }
}
