package com.example.edam.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
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
     * Webhook 响应体读取线程池。
     *
     * <p><b>为什么需要它</b>（2026-11-14 代码审计第七轮）：
     * {@code HttpRequest.timeout(30s)} 只覆盖到<b>响应头</b> ——
     * 用 {@code BodyHandlers.ofInputStream()} 时，返回头之后读取 body
     * 是调用方自己的事，<b>不受那个超时约束</b>。
     *
     * <p>而 {@code readNBytes(64KB)} 会一直读到 EOF 或凑满 64KB：
     * 一个返回 {@code 200} + 少量字节然后<b>保持连接不断</b>的对端
     * （slow-loris）就能把投递线程永久卡住。
     * {@code webhookExecutor} 上限 16 线程 ⇒ 16 个挂死后
     * <b>全部 webhook 投递停摆</b>。
     *
     * <p>所以把"读 body"放到一个独立的小池里，用 {@code Future.get(超时)}
     * 兜住 —— 超时就当作投递失败（走重试），线程不会被拖死。
     *
     * <p>拒绝策略用 {@code AbortPolicy}（不是 CallerRuns）：队列满时直接
     * 抛 {@code RejectedExecutionException}，被投递逻辑捕获 ⇒ 记一次失败。
     * 用 CallerRuns 的话，调用线程自己读 —— 又回到"永久卡住"的老问题。
     */
    @Bean("webhookReadExecutor")
    public ExecutorService webhookReadExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(64);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("webhook-read-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        // 返回**底层**的 ThreadPoolExecutor（它才是 ExecutorService）。
        // ThreadPoolTaskExecutor 自己不是 ExecutorService —— 它只是包了一层
        // 生命周期管理；调用方要的是 submit()/Future 语义。
        log.info("webhook_read_executor_initialized, core=4 max=8 queue=64");
        return executor.getThreadPoolExecutor();
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
