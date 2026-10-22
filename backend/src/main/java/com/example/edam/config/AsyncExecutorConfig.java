package com.example.edam.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步执行器配置。
 *
 * <p><b>为什么必须有</b>：{@code WebhookDeliveryService.deliver} 上标了
 * {@code @Async("webhookExecutor")}，但全仓库**没有任何** 提供该名字的
 * bean —— Spring 在首次调用时抛
 * {@code NoSuchBeanDefinitionException: No executor bean named 'webhookExecutor'}，
 * webhook 投递在第一次就炸。
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncExecutorConfig {

    /**
     * webhook 投递线程池。
     *
     * <p>队列满时用 {@code CallerRunsPolicy} —— 由调用线程自己跑，
     * 宁可让调用方变慢，也不能静默丢任务。
     */
    @Bean("webhookExecutor")
    public Executor webhookExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("webhook-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅停机：等正在跑的投递完成（webhook 丢了比慢一点更糟）
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("webhook_executor_initialized, core=4 max=16 queue=500");
        return executor;
    }
}
