package com.kuros.kurosbackend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 自定义线程池配置，用于验证码异步发送。
 *
 * 为什么不用 Spring 默认的 @Async 线程池？
 * 默认线程池（SimpleAsyncTaskExecutor）每次创建新线程，不复用，高并发下会 OOM。
 * 自定义线程池可以控制核心线程数、队列容量和拒绝策略，
 * 短信发送是 IO 密集型任务，核心线程数设为 CPU 核数 * 2 是常见实践。
 *
 * 对应小哈书第五章 5.4：自定义线程池实现异步发送验证码。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 短信发送专用线程池。
     * 拒绝策略用 CallerRunsPolicy：队列满时由调用线程执行，保证验证码不丢。
     */
    @Bean("smsExecutor")
    public Executor smsExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Runtime.getRuntime().availableProcessors() * 2);
        executor.setMaxPoolSize(Runtime.getRuntime().availableProcessors() * 4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("sms-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
