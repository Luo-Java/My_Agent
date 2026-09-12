package org.luo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 应用线程池配置。
 * <p>
 * 后台异步任务（记忆合并）与并发阻塞调用（多图视觉识别）各自使用专用线程池，与对话主链路使用的
 * Reactor {@code boundedElastic} 调度器完全隔离：任务再慢也不挤占对话执行线程；
 * 线程名前缀便于日志排查；Spring 托管生命周期，应用关闭时优雅等待任务完成。
 */
@Configuration
public class ExecutorConfig {

    /**
     * 记忆合并专用线程池。
     * <p>
     * 低优先级后台任务，故意配成小池子：核心 2、最大 4、有界队列 1000；
     * 并发合并不会超过 4 个（记忆合并本身有会话级 merging 去重，同一会话只会有一个在跑）。
     * 队列满时抛 {@link java.util.concurrent.RejectedExecutionException}，由调用方捕获记日志即可
     * （少合并一次不影响主流程，下一轮对话结束会再检查）。
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
     * 视觉识别专用线程池。
     * <p>
     * 多图识别是「阻塞 IO + 相互独立的调用」，并发执行可把 N 张图的串行等待压成 1 轮；
     * 与对话主链路、记忆合并互不干扰。单次请求最多 5 张图，核心 4 / 最大 8 / 有界队列 64 足够。
     * 队列满时抛 {@link java.util.concurrent.RejectedExecutionException}，由调用方捕获降级为占位 caption。
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
     * 前置链预取专用线程池（多轮查询改写）。
     * <p>
     * 一轮对话在「正式回答」之前有三段彼此独立的模型往返：智能路由、参数抽取、检索问题改写。
     * 前两者有数据依赖（参数抽取需要路由结果），只有改写完全独立——它只看用户原话与会话历史。
     * 故把改写提交到本池异步执行，与调用方的前置链并行，把它的耗时整个藏到路由/参数抽取背后。
     * <p>
     * 池子按「并发对话数」配置：核心 4 / 最大 8 / 有界队列 256。任务本身很短（一次模型往返），
     * 队列满时抛 {@link java.util.concurrent.RejectedExecutionException}，调用方捕获后回退为同步改写
     * （退化成本次改造前的行为，不影响正确性）。
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
     * 链路追踪落库专用线程池。
     * <p>
     * 追踪是纯旁路数据，必须在回复产出<b>之后</b>异步写，绝不能挡在用户看到答案之前；
     * 池子配得很小（核心 2 / 最大 4 / 队列 2000）——积压时宁可丢追踪记录，也不抢占对话资源。
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
}
