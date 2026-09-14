package org.luo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 应用线程池配置。
 * <p>
 * 后台异步任务（记忆合并 / 追踪落库）与并发阻塞调用（多图视觉识别 / 前置链预取）各用专用线程池，
 * 与对话主链路的 Reactor {@code boundedElastic} 完全隔离：任务再慢也不挤占对话执行线程；
 * 线程名前缀便于日志排查；Spring 托管生命周期，应用关闭时优雅等待。
 */
@Configuration
public class ExecutorConfig {

    /**
     * 记忆合并专用线程池。低优先级后台任务，故意配成小池子（核心 2 / 最大 4 / 队列 1000）；
     * 记忆合并有会话级 merging 去重，同一会话只会有一个在跑。队列满抛
     * {@link java.util.concurrent.RejectedExecutionException}，由调用方捕获记日志即可（少合并一次不影响主流程）。
     */
    @Bean("memoryMergeExecutor")
    public Executor memoryMergeExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(1000);
        ex.setKeepAliveSeconds(60);
        ex.setThreadNamePrefix("memory-merge-");
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(10);
        ex.initialize();
        return ex;
    }

    /**
     * 视觉识别专用线程池。多图识别是「阻塞 IO + 相互独立的调用」，并发可把 N 张图的串行等待压成 1 轮；
     * 单次请求最多 5 张图，核心 4 / 最大 8 / 队列 64 足够。队列满抛
     * {@link java.util.concurrent.RejectedExecutionException}，由调用方捕获降级为占位 caption。
     */
    @Bean("visionExecutor")
    public Executor visionExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(4);
        ex.setMaxPoolSize(8);
        ex.setQueueCapacity(64);
        ex.setKeepAliveSeconds(60);
        ex.setThreadNamePrefix("vision-");
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(10);
        ex.initialize();
        return ex;
    }

    /**
     * 前置链预取专用线程池（多轮查询改写）。一轮对话在「正式回答」前有三段独立模型往返：路由、参数抽取、
     * 检索问题改写；前两者有数据依赖，只有改写完全独立（只看用户原话与会话历史），故提交到本池与前置链
     * 并行，把它的耗时藏到路由/参数抽取背后。核心 4 / 最大 8 / 队列 256；队列满抛
     * {@link java.util.concurrent.RejectedExecutionException}，调用方捕获后回退为同步改写（不影响正确性）。
     */
    @Bean("roundPrefetchExecutor")
    public Executor roundPrefetchExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(4);
        ex.setMaxPoolSize(8);
        ex.setQueueCapacity(256);
        ex.setKeepAliveSeconds(60);
        ex.setThreadNamePrefix("prefetch-");
        ex.setWaitForTasksToCompleteOnShutdown(false);
        ex.initialize();
        return ex;
    }

    /**
     * 链路追踪落库专用线程池。追踪是纯旁路数据，必须在回复产出<b>之后</b>异步写，绝不能挡在用户看到答案
     * 之前；池子配得很小（核心 2 / 最大 4 / 队列 2000）——积压时宁可丢追踪记录，也不抢占对话资源。
     * 队列满抛 {@link java.util.concurrent.RejectedExecutionException}，由 TraceService 捕获记 debug 日志。
     */
    @Bean("traceExecutor")
    public Executor traceExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(2000);
        ex.setKeepAliveSeconds(60);
        ex.setThreadNamePrefix("trace-");
        ex.setWaitForTasksToCompleteOnShutdown(false);
        ex.initialize();
        return ex;
    }

    /**
     * SSE 心跳专用调度器。原先心跳跑在 Reactor {@code Schedulers.parallel()} 上，与「打字机」的
     * {@code delayElements} 及 Reactor 内部操作共用同一批线程；而心跳要调 {@code SseEmitter.send()}，
     * 对慢客户端这是<b>阻塞</b>调用——只要几个连接卡在发送上，parallel 就被占满、把全站心跳一起拖死。
     * 拆出独立小池子隔离风险：线程带名字便于排查、守护线程不阻止 JVM 退出，生命周期由 Spring 托管。
     * 调用方用 {@code scheduleWithFixedDelay} 提交：下一次从上次执行完成之后才开始计时，慢客户端只让其顺延。
     */
    @Bean(name = "sseHeartbeatScheduler")
    public ScheduledExecutorService sseHeartbeatScheduler() {
        AtomicInteger seq = new AtomicInteger();
        return Executors.newScheduledThreadPool(8, r -> {
            Thread t = new Thread(r, "sse-heartbeat-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }
}
