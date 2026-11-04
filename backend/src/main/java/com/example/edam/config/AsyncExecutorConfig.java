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

    /**
     * 审计日志写入线程池。
     *
     * <p>为什么单独一个池：审计写入是**高频**的（现在每个受控接口都会记一条），
     * 而 webhook 投递是**低频长耗时**的。两者挤在一个池里，
     * 一次慢的 webhook 投递就能把审计写入堵住 —— 而审计堵住意味着
     * 审计记录丢失（合规风险）。
     *
     * <p>拒绝策略同样是 {@code CallerRunsPolicy}：队列满时由请求线程自己写，
     * 宁可这个请求慢一点，也不能丢审计记录。
     */
    @Bean("auditExecutor")
    public Executor auditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(2000);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("audit-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("audit_executor_initialized, core=2 max=4 queue=2000");
        return executor;
    }
}
