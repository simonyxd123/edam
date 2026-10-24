package com.example.edam.controller;

import com.example.edam.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查 Controller
 * 对应 openapi.yaml tag: health
 */
@RestController
@RequestMapping("/health")
@RequiredArgsConstructor
@Slf4j
public class HealthController {

    @Value("${spring.application.name}")
    private String appName;

    @Value("${spring.application.version:3.1.0}")
    private String version;

    private final StringRedisTemplate redisTemplate;
    private final ConnectionFactory rabbitConnectionFactory;
    private final SysUserRepository userRepository;

    @GetMapping("/live")
    public Map<String, Object> liveness() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "alive");
        result.put("uptime_sec", java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        result.put("pid", ProcessHandle.current().pid());
        return result;
    }

    @GetMapping("/ready")
    public Map<String, Object> readiness() {
        Map<String, Object> components = checkComponents();
        boolean allOk = components.values().stream()
                .allMatch(v -> "ok".equals(((Map<?, ?>) v).get("status")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", allOk ? "ok" : "degraded");
        result.put("version", version);
        result.put("components", components);
        // 初始管理员自举状态。
        //
        // 自举失败不阻断启动（服务仍可用），但症状是"服务正常却登不进去" ——
        // 前端只显示"工号或密码错误"，数据库里 sys_user 一条都没有。
        // 不把状态暴露出来，排查时完全没线索。
        result.put("admin_bootstrap", com.example.edam.bootstrap.AdminBootstrap.getBootstrapStatus());
        String bootstrapError = com.example.edam.bootstrap.AdminBootstrap.getBootstrapError();
        if (bootstrapError != null) {
            result.put("admin_bootstrap_error", bootstrapError);
        }
        return allOk ? result : Map.of("status", "down", "components", components);
    }

    @GetMapping
    public Map<String, Object> overall() {
        return readiness();
    }

    @GetMapping("/components")
    public Map<String, Object> components() {
        return checkComponents();
    }

    private Map<String, Object> checkComponents() {
        Map<String, Object> components = new LinkedHashMap<>();

        // MySQL
        Map<String, Object> mysql = new LinkedHashMap<>();
        try {
            long start = System.currentTimeMillis();
            userRepository.selectCount(null);
            mysql.put("status", "ok");
            mysql.put("latency_ms", System.currentTimeMillis() - start);
        } catch (Exception e) {
            mysql.put("status", "down");
            // 不对外暴露异常消息 —— 里面通常含 JDBC URL / 主机名 / 端口。
            // 详细信息只进服务端日志。
            log.warn("health_mysql_down: {}", e.getMessage());
        }
        components.put("mysql", mysql);

        // Redis
        Map<String, Object> redis = new LinkedHashMap<>();
        try {
            long start = System.currentTimeMillis();
            redisTemplate.opsForValue().get("health:probe");
            redis.put("status", "ok");
            redis.put("latency_ms", System.currentTimeMillis() - start);
        } catch (Exception e) {
            redis.put("status", "down");
            log.warn("health_redis_down: {}", e.getMessage());
        }
        components.put("redis", redis);

        // RabbitMQ
        Map<String, Object> rabbit = new LinkedHashMap<>();
        try (var conn = rabbitConnectionFactory.createConnection()) {
            rabbit.put("status", conn.isOpen() ? "ok" : "down");
        } catch (Exception e) {
            rabbit.put("status", "down");
            log.warn("health_rabbitmq_down: {}", e.getMessage());
        }
        components.put("rabbitmq", rabbit);

        return components;
    }
}