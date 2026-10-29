package com.example.edam.controller;

import com.example.edam.bootstrap.AdminBootstrap;
import com.example.edam.config.RabbitConfig;
import com.example.edam.repository.SysUserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 健康检查 Controller
 * 对应 openapi.yaml tag: health
 *
 * <p><b>三个端点各管一件事，不能互相顶替</b>（方案书 9.2 可观测性）：
 * <ul>
 *   <li>{@code /health/live} —— <b>进程还活着吗</b>。K8s livenessProbe 用。
 *       它<b>只看进程自己</b>，绝不碰任何外部依赖：数据库抖一下就把所有
 *       Pod 重启，是把局部故障放大成雪崩的经典做法。</li>
 *   <li>{@code /health/ready} —— <b>这个 Pod 现在能应答请求吗</b>。
 *       K8s readinessProbe 用。</li>
 *   <li>{@code /health/components} —— <b>每个依赖各自什么状态</b>。
 *       管理员用（OpenAPI 里这个端点是 401 受保护的）。</li>
 * </ul>
 *
 * <p><b>为什么 ready 必须返回 503</b>：K8s 的 httpGet 探针<b>只看 HTTP
 * 状态码，不看 body</b>。而 OpenAPI 里 {@code /health/ready} 明确声明了
 * {@code 503 未就绪}，e2e（{@code e2e/cypress/e2e/01-auth.cy.js}）也断言
 * 状态码 ∈ {{200, 503}}。此前实现无论依赖是否可用都返回 <b>200</b> ——
 * 于是 MySQL 挂了，探针照样绿灯，坏 Pod 继续留在 Endpoints 里接流量，
 * 流量全部撞到 500 上。<b>探针成了摆设。</b>
 *
 * <p><b>什么时候判 503（down）</b>：只有 MySQL —— 任何一条业务请求都要
 * 读它，MySQL 不可达 ⇒ 这个 Pod 一个请求都答不出来。Redis / RabbitMQ /
 * MinIO / Vault 是<b>共享</b>依赖，它们故障时把<b>所有</b>副本一起摘出
 * Service，只会把局部故障放大成整体不可用（而且摘出来也救不回来，因为
 * 坏的是共享组件本身）。所以它们故障只降级为 {@code degraded}，保持 200，
 * 靠 {@code /health/components} 和告警去看细节。
 */
@RestController
@RequestMapping("/health")
@RequiredArgsConstructor
@Slf4j
public class HealthController {

    /**
     * 单个外部依赖的探测超时（毫秒）。
     *
     * <p><b>为什么必须设</b>：各客户端自己的超时都远大于探针该花的时间 ——
     * Hikari 的 connection-timeout 是 30s、RabbitMQ 默认 60s。K8s 的
     * readinessProbe 默认 timeoutSeconds 只有 <b>1s</b>，探针超时会被判为
     * <b>失败</b> ⇒ Pod 被摘出 Endpoints。也就是说，Redis 慢一点就能把
     * 所有 Pod 一起摘掉 —— 把"某个共享依赖变慢"放大成"全站不可用"。
     *
     * <p>所以每个探测都自带上限：慢 ⇒ 判 down/degraded，但<b>不拖住整个响应</b>。
     */
    private static final long PROBE_TIMEOUT_MS = 1500;

    /**
     * 探测用的线程池。
     *
     * <p>探测是阻塞 IO（等连接、等 socket），必须放到独立线程里，
     * 才能在超时后<b>放弃等待</b>而不是把 HTTP 请求线程挂住。
     * 守护线程 + 允许核心线程超时回收：探针线程泄漏不会拖住 JVM 退出。
     */
    private final ExecutorService probePool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "health-probe");
        t.setDaemon(true);
        return t;
    });

    @Value("${spring.application.version:3.1.0}")
    private String version;

    /**
     * Worker 回调令牌；为空则 worker 的回调一律被拒（fail-closed）。
     *
     * <p>裸机部署时这块最容易漏：K8s 侧两边都从同一个 secret 取
     * {@code worker-token}，而裸机侧没人管 —— 漏配的表现是
     * "转码早就完成了，产物也在 MinIO 上，但 hls_status 永远 pending，
     * 用户点播放还是 404"，而日志里只有一行 unauthorized。
     */
    @Value("${edam.worker.token:}")
    private String workerToken;

    private final StringRedisTemplate redisTemplate;
    private final ConnectionFactory rabbitConnectionFactory;
    private final RabbitAdmin rabbitAdmin;
    private final SysUserRepository userRepository;
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    @Value("${minio.endpoint:http://localhost:9000}")
    private String minioEndpoint;

    @Value("${vault.uri:http://localhost:8200}")
    private String vaultUri;

    /**
     * 存活探针：只回答"进程还在"。
     *
     * <p><b>绝不碰外部依赖</b> —— 否则数据库一抖，K8s 会把所有副本一起
     * 重启（CrashLoopBackOff），把一次数据库抖动变成全站不可用。
     */
    @GetMapping("/live")
    public Map<String, Object> liveness() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "alive");
        result.put("uptime_sec", java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        result.put("pid", ProcessHandle.current().pid());
        return result;
    }

    /**
     * 就绪探针：MySQL 不可用 ⇒ 这个 Pod 不能接流量 ⇒ 503，让 K8s 把它
     * 从 Endpoints 摘掉；其余依赖故障只降级（200）。
     *
     * <p>响应体里<b>始终</b>带着初始管理员的自举状态：运维排查"服务起来了
     * 但登不进去"时，恰恰是最需要这个字段的时候 —— 它要是随 503 一起
     * 消失，排查就又变成瞎猜。
     */
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> readiness() {
        Map<String, Object> components = probeCoreComponents();

        boolean mysqlDown = "down".equals(((Map<?, ?>) components.get("mysql")).get("status"));
        boolean anyDown = components.values().stream()
                .anyMatch(c -> "down".equals(((Map<?, ?>) c).get("status")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", mysqlDown ? "down" : (anyDown ? "degraded" : "ok"));
        result.put("version", version);
        result.put("uptime_sec", java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        result.put("timestamp", OffsetDateTime.now().toString());
        result.put("components", components);
        // 初始管理员自举状态（失败时服务仍能起来，但没人能登录）——见 AdminBootstrap
        result.put("admin_bootstrap", AdminBootstrap.getBootstrapStatus());
        String bootstrapError = AdminBootstrap.getBootstrapError();
        if (bootstrapError != null) {
            result.put("admin_bootstrap_error", bootstrapError);
        }

        // Worker 回调通道的配置状态。
        //
        // 为什么放在 ready 里：运维脚本、巡检和人都已经在看这个端点了，
        // 而"回调通道没通"这件事**不会**让后端自己变红 —— 它只在
        // worker 那边表现为"转码完成了但状态没更新"，最后变成用户
        // 点播放还是 404。把信号放在已经在看的端点上，才有人能提前发现。
        //
        // 刻意**不影响** HTTP 状态码：后端本身完全能服务，
        // 只是 worker 的回调进不来 —— 让整个后端 503 反而是过度反应。
        Map<String, Object> workerCallback = new LinkedHashMap<>();
        boolean tokenConfigured = workerToken != null && !workerToken.isBlank();
        workerCallback.put("token_configured", tokenConfigured);
        workerCallback.put("status", tokenConfigured ? "ok" : "misconfigured");
        if (!tokenConfigured) {
            workerCallback.put("hint",
                "edam.worker.token 未配置 —— worker 的回调一律被拒（401），"
                + "转码完成后 hls_status 不会更新，用户点播放仍会 404");
        }
        result.put("worker_callback", workerCallback);

        return mysqlDown
                ? ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result)
                : ResponseEntity.ok(result);
    }

    /**
     * 并发探测三个核心依赖。
     *
     * <p><b>为什么并发</b>：串行的话最坏耗时是三者之和（3 × 1.5s = 4.5s），
     * 而 K8s readinessProbe 默认只等 1s ⇒ 探针会因超时被判失败，把
     * <b>完全健康</b>的 Pod 摘出 Endpoints。并发后总耗时 ≈ 最慢的那一个。
     */
    private Map<String, Object> probeCoreComponents() {
        Map<String, Object> components = new LinkedHashMap<>();
        CompletableFuture<Map<String, Object>> mysql = CompletableFuture.supplyAsync(this::probeMysql, probePool);
        CompletableFuture<Map<String, Object>> redis = CompletableFuture.supplyAsync(this::probeRedis, probePool);
        CompletableFuture<Map<String, Object>> rabbit = CompletableFuture.supplyAsync(this::probeRabbitMq, probePool);
        components.put("mysql", join(mysql));
        components.put("redis", join(redis));
        components.put("rabbitmq", join(rabbit));
        return components;
    }

    /** 取探测结果；探测线程本身异常时按 down 处理，不让一个组件拖垮整个端点。 */
    private Map<String, Object> join(CompletableFuture<Map<String, Object>> future) {
        try {
            return future.get(PROBE_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Map<String, Object> down = new LinkedHashMap<>();
            down.put("status", "down");
            down.put("latency_ms", PROBE_TIMEOUT_MS * 2);
            log.warn("health_probe_failed: {}", describe(e));
            return down;
        }
    }

    /**
     * 总体健康检查（兼容旧版）。与 /health/ready 同语义。
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> overall() {
        return readiness();
    }

    /**
     * 各依赖组件的详细状态（管理员用）。
     *
     * <p>形状与 OpenAPI 的 {@code ComponentsHealth} 一致：
     * {@code {overall, components:{...}}}。
     *
     * <p>与 {@code /health/ready} 的区别：这里做<b>全量</b>探测（含 MinIO、
     * Vault），耗时更高；ready 只做决定"能不能接流量"所需的那几个。
     * 所以这个端点受鉴权保护（OpenAPI 声明 401），而 ready 是公开的
     * （K8s 探针不带 token）。
     */
    @GetMapping("/components")
    public Map<String, Object> components() {
        Map<String, Object> components = new LinkedHashMap<>();
        components.putAll(probeCoreComponents());
        components.put("minio", probeMinio());
        components.put("vault", probeVault());

        boolean anyDown = components.values().stream()
                .anyMatch(c -> "down".equals(((Map<?, ?>) c).get("status")));
        boolean mysqlDown = "down".equals(((Map<?, ?>) components.get("mysql")).get("status"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("overall", mysqlDown ? "down" : (anyDown ? "degraded" : "ok"));
        result.put("components", components);
        result.put("timestamp", OffsetDateTime.now().toString());
        return result;
    }

    // ------------------------------------------------------------------
    // 各依赖的探测实现
    // ------------------------------------------------------------------

    private Map<String, Object> probeMysql() {
        Map<String, Object> mysql = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try {
            // 真正打一次库：只 ping 端口的话，库在但表结构坏/连接池耗尽时看不出来。
            // 放在独立线程里并带超时：Hikari 的 connection-timeout 是 30s，
            // 而 K8s 探针只等 1s —— 不设上限的话探针会超时失败。
            probeWithTimeout(() -> {
                userRepository.selectCount(null);
                return null;
            });
            mysql.put("status", "ok");
        } catch (Exception e) {
            mysql.put("status", "down");
            // 不对外暴露异常消息 —— 里面通常含 JDBC URL / 主机名 / 端口
            log.warn("health_mysql_down: {}", describe(e));
        }
        mysql.put("latency_ms", System.currentTimeMillis() - start);
        addPoolStats(mysql);
        return mysql;
    }

    /**
     * 在独立线程里执行探测，最多等 {@link #PROBE_TIMEOUT_MS}。
     *
     * <p>超时按"探测失败"处理（抛异常），但<b>不无限等</b>：等待被放弃后
     * 底层线程仍可能在客户端自己的超时里才返回，那是后台线程的事，
     * 不会拖住 HTTP 响应。
     */
    private void probeWithTimeout(Callable<Void> probe) throws Exception {
        Future<Void> future = probePool.submit(probe);
        try {
            future.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("探测超时（>" + PROBE_TIMEOUT_MS + "ms）");
        }
    }

    /** 连接池水位：连接池打满时业务接口其实已经不可用了，光看"库通"看不出来。 */
    private void addPoolStats(Map<String, Object> mysql) {
        if (!(dataSource instanceof HikariDataSource hikari)) {
            return;
        }
        try {
            var pool = hikari.getHikariPoolMXBean();
            if (pool == null) {
                return;
            }
            mysql.put("pool_active", pool.getActiveConnections());
            mysql.put("pool_idle", pool.getIdleConnections());
        } catch (Exception e) {
            log.debug("health_pool_stats_unavailable: {}", e.getMessage());
        }
    }

    private Map<String, Object> probeRedis() {
        Map<String, Object> redis = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try {
            // 写一个探针键再读回来：只 GET 不写，读缓存里的旧值也能"成功"
            probeWithTimeout(() -> {
                redisTemplate.opsForValue().set("health:probe", "1", Duration.ofSeconds(30));
                redisTemplate.opsForValue().get("health:probe");
                return null;
            });
            redis.put("status", "ok");
        } catch (Exception e) {
            redis.put("status", "down");
            log.warn("health_redis_down: {}", describe(e));
        }
        redis.put("latency_ms", System.currentTimeMillis() - start);
        addRedisMemory(redis);
        return redis;
    }

    /** Redis 内存水位：Redis 内存打满会触发 key 淘汰甚至写入失败。 */
    private void addRedisMemory(Map<String, Object> redis) {
        try {
            Properties info = redisTemplate.execute((RedisCallback<Properties>) conn ->
                    conn.serverCommands().info("memory"));
            if (info == null) {
                return;
            }
            String used = info.getProperty("used_memory");
            if (used != null) {
                redis.put("memory_used_mb", Long.parseLong(used) / 1024 / 1024);
            }
        } catch (Exception e) {
            log.debug("health_redis_memory_unavailable: {}", describe(e));
        }
    }

    private Map<String, Object> probeRabbitMq() {
        Map<String, Object> rabbit = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try {
            // createConnection() 的默认超时是 60s —— 不设上限的话，
            // RabbitMQ 挂掉时探针会卡 60s，早就被 K8s 判超时了
            probeWithTimeout(() -> {
                try (var conn = rabbitConnectionFactory.createConnection()) {
                    if (!conn.isOpen()) {
                        throw new IllegalStateException("连接已关闭");
                    }
                }
                return null;
            });
            rabbit.put("status", "ok");
        } catch (Exception e) {
            rabbit.put("status", "down");
            log.warn("health_rabbitmq_down: {}", describe(e));
        }
        rabbit.put("latency_ms", System.currentTimeMillis() - start);
        addQueueDepth(rabbit);
        return rabbit;
    }

    /**
     * 队列积压深度：转码/水印任务全靠这三个队列串起来，
     * 积压直接对应"视频什么时候能看"。
     */
    private void addQueueDepth(Map<String, Object> rabbit) {
        try {
            QueueInformation info = rabbitAdmin.getQueueInfo(RabbitConfig.VIDEO_PREPROCESS_QUEUE);
            if (info != null) {
                rabbit.put("queue_depth", info.getMessageCount());
            }
        } catch (Exception e) {
            log.debug("health_queue_depth_unavailable: {}", describe(e));
        }
    }

    /**
     * MinIO 探针：打 MinIO 自带的 {@code /minio/health/live}。
     *
     * <p>不用 SDK 的 bucketExists —— 那是业务 bucket，而 bucket 是首次上传
     * 时才懒创建的，新装的环境 bucket 还不存在，会被误判成 MinIO 挂了。
     */
    private Map<String, Object> probeMinio() {
        Map<String, Object> minio = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try {
            HttpResponse<String> resp = httpGet(minioEndpoint + "/minio/health/live");
            boolean ok = resp.statusCode() >= 200 && resp.statusCode() < 300;
            minio.put("status", ok ? "ok" : "down");
            if (!ok) {
                log.warn("health_minio_down: http {}", resp.statusCode());
            }
        } catch (Exception e) {
            minio.put("status", "down");
            log.warn("health_minio_down: {}", describe(e));
        }
        minio.put("latency_ms", System.currentTimeMillis() - start);
        addMinioDiskUsage(minio);
        return minio;
    }

    /**
     * MinIO 磁盘用量。
     *
     * <p>MinIO 是<b>单点</b>：它挂了视频就全存不下，而且它同时是解密/播放
     * 的必经之路。它不像 Redis 那样"坏了还能降级"——所以用量必须能提前看到，
     * 而不是等写不进去了才发现。
     *
     * <p>cluster 接口要 admin 凭据，老版本/非 admin 会拿不到；
     * 拿不到就只是不报这个字段，不影响主状态。
     */
    private void addMinioDiskUsage(Map<String, Object> minio) {
        try {
            HttpResponse<String> resp = httpGet(minioEndpoint + "/minio/health/cluster");
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return;
            }
            JsonNode storage = objectMapper.readTree(resp.body())
                    .path("servers").path(0).path("storage");
            long total = storage.path("total").asLong(-1);
            long free = storage.path("free").asLong(-1);
            if (total > 0 && free >= 0) {
                minio.put("disk_used_gb", (total - free) / 1024 / 1024 / 1024);
            }
        } catch (Exception e) {
            log.debug("health_minio_disk_unavailable: {}", describe(e));
        }
    }

    /**
     * Vault 探针：打 {@code /v1/sys/health}。
     *
     * <p>{@code sealed=true} 表示 Vault 已封存 —— 服务本身还活着，但取不到密钥。
     * 这与"连不上"是两种不同故障，运维要分得清，所以单独报出来。
     */
    private Map<String, Object> probeVault() {
        Map<String, Object> vault = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try {
            HttpResponse<String> resp = httpGet(vaultUri + "/v1/sys/health");
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                vault.put("status", "ok");
                JsonNode body = objectMapper.readTree(resp.body());
                boolean sealed = body.path("sealed").asBoolean(false);
                vault.put("sealed", sealed);
                if (sealed) {
                    vault.put("status", "degraded");
                    log.warn("health_vault_sealed: Vault 已封存，取不到密钥");
                }
            } else {
                vault.put("status", "down");
                log.warn("health_vault_down: http {}", resp.statusCode());
            }
        } catch (Exception e) {
            vault.put("status", "down");
            log.warn("health_vault_down: {}", describe(e));
        }
        vault.put("latency_ms", System.currentTimeMillis() - start);
        return vault;
    }

    private HttpResponse<String> httpGet(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(PROBE_TIMEOUT_MS))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(PROBE_TIMEOUT_MS))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 把异常变成一行能读的日志。
     *
     * <p>只打 {@code e.getMessage()} 是不够的：连接被拒这类异常的
     * message 经常是 {@code null}（{@code ConnectException} 就是），
     * 日志里只剩一句 "health_minio_down: null"，等于没打。
     * 带上异常类型才有排查价值。
     */
    private static String describe(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
