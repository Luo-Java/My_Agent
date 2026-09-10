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
}
