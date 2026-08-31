package org.luo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 应用线程池配置。
 * <p>
 * 记忆合并等「后台异步任务」统一使用专用线程池，与对话主链路使用的 Reactor
 * {@code boundedElastic} 调度器完全隔离：合并再慢也不挤占对话执行线程；
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
}
