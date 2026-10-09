package org.luo.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 定时任务调度器注册。
 * <p>
 * <b>为什么不能只靠默认的</b>：Spring Boot 内建的任务调度器是<b>单线程</b>池，而本项目三个
 * {@code @Scheduled} 都要用到它 ——
 * <ul>
 *   <li>{@code AlertService.check}：每 10 分钟一轮窗口聚合；</li>
 *   <li>{@code ScheduledTaskService.tick}：每 30 秒扫一次到点任务，<b>执行体是同步跑真实模型对话</b>；</li>
 *   <li>{@code AlertService.report}：cron {@code 0 0 9 * * *} 的周期报告。</li>
 * </ul>
 * 共用单线程的后果不是「慢一点」，是三档具体故障：
 * <ol>
 *   <li>一个任务耗时 5 分钟，其余两个这 5 分钟<b>完全不执行</b>；</li>
 *   <li>cron 型任务错过触发点<b>当天永久丢失</b>（Spring 不会补跑错过的 cron）；</li>
 *   <li>多实例部署时各实例扫描相位不同，{@code next_run_at} 的 CAS 只保证「同一时刻一个执行者」，
 *       保证不了「一个周期一次执行」—— cron 周期短于单轮耗时（如每分钟一次、耗时 90 秒）时，
 *       同一周期会被执行两次，即<b>双倍真实模型花费</b>。</li>
 * </ol>
 *
 * <h2>参数取值理由</h2>
 * <ul>
 *   <li><b>pool-size = 4</b>：同时在跑的调度任务最多三个（各留一个余量给后续新增的定时任务）。
 *       不需要更大 —— 每轮内部已经把一批任务串行处理了，瓶颈在模型调用延迟而非调度并发；</li>
 *   <li><b>waitForTasksToCompleteOnShutdown = true</b>：停机时让在跑的任务收尾而不是直接掐断。
 *       {@code execute} 里正在进行的模型调用被掐断，会留下 {@code last_status=RUNNING} 的悬挂状态
 *       （且下次扫描要等到 {@code next_run_at} 才推进）；</li>
 *   <li><b>awaitTerminationSeconds = 30</b>：收尾等待上限，避免停机被无限拖住。</li>
 * </ul>
 *
 * <h2>为什么不用配置项</h2>
 * {@code spring.task.scheduling.pool-size} 是 Boot 自动配置的默认值，走它就没法在同一处声明关停策略与
 * 线程名；而定时任务排查时最缺的就是「这条日志来自哪个池」，故线程名前缀写死在这里。
 * 池大小<b>刻意不做成配置项</b>：调大它并不能提高吞吐（瓶颈在模型延迟），只会让并发重复执行更容易发生 ——
 * 那正是上面第 3 条故障的放大器。
 */
@Configuration
public class SchedulingConfig {

    /** 同时可跑的调度任务数（当前三个 + 一个余量）。 */
    private static final int POOL_SIZE = 4;

    /** 停机时等待在跑任务收尾的秒数上限。 */
    private static final int AWAIT_TERMINATION_SECONDS = 30;

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(POOL_SIZE);
        scheduler.setThreadNamePrefix("my-agent-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS);
        // 关停时把还没到点的任务（如周期报告）从调度器上摘掉，避免关停过程中被触发
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
