package com.example.edam.controller;

import com.example.edam.bootstrap.AdminBootstrap;
import com.example.edam.repository.SysUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link HealthController} 单测。
 *
 * <p><b>为什么这几个用例必须存在</b>：健康端点出过一次**线上级故障**——
 * {@code /health/ready} 无论依赖是否可用都返回 <b>200</b>。而 K8s 的
 * httpGet 探针<b>只看 HTTP 状态码</b>，于是 MySQL 挂了探针照样绿灯，
 * 坏 Pod 继续留在 Endpoints 里接流量，流量全部撞 500 —— <b>探针成了摆设</b>。
 *
 * <p>用例会锁住三条不变量：
 * <ol>
 *   <li>MySQL 不可用 ⇒ {@code /health/ready} 返回 <b>503</b>（探针必须能失败）</li>
 *   <li>503 的响应体里<b>仍然带着 admin_bootstrap</b> —— 运维排查
 *       "服务起来了但登不进去"时最需要的就是这个字段，它不能随 503 一起消失</li>
 *   <li>{@code /health/live} <b>绝不碰外部依赖</b>：它只回答"进程还在"。
 *       一旦它去 ping 数据库，数据库一抖 K8s 会把所有副本一起重启
 *       （CrashLoopBackOff），把局部故障放大成雪崩</li>
 * </ol>
 */
class HealthControllerTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ConnectionFactory rabbitConnectionFactory;
    private Connection rabbitConnection;
    private SysUserRepository userRepository;
    private HealthController controller;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        rabbitConnectionFactory = mock(ConnectionFactory.class);
        rabbitConnection = mock(Connection.class);
        when(rabbitConnection.isOpen()).thenReturn(true);
        when(rabbitConnectionFactory.createConnection()).thenReturn(rabbitConnection);

        userRepository = mock(SysUserRepository.class);

        controller = new HealthController(
                redisTemplate,
                rabbitConnectionFactory,
                mock(RabbitAdmin.class),
                userRepository,
                mock(DataSource.class),
                new ObjectMapper());
        setField(controller, "version", "3.1.0");
        setField(controller, "minioEndpoint", "http://127.0.0.1:1");
        setField(controller, "vaultUri", "http://127.0.0.1:1");
    }

    @AfterEach
    void tearDown() {
        // AdminBootstrap 的自举状态是 static 的，会漏到别的用例里
        setStatic(AdminBootstrap.class, "bootstrapStatus", "not-run");
        setStatic(AdminBootstrap.class, "bootstrapError", null);
    }

    @Test
    @DisplayName("依赖全好时 /health/ready 返回 200")
    void readyReturns200WhenAllHealthy() {
        ResponseEntity<Map<String, Object>> resp = controller.readiness();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("ok", resp.getBody().get("status"));
    }

    @Test
    @DisplayName("MySQL 不可用时 /health/ready 返回 503 —— 探针必须能失败")
    void readyReturns503WhenMySqlDown() {
        when(userRepository.selectCount(any())).thenThrow(new RuntimeException("Communications link failure"));

        ResponseEntity<Map<String, Object>> resp = controller.readiness();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode(),
                "MySQL 挂了必须返回 503，否则 K8s readinessProbe 永远绿灯，坏 Pod 继续接流量");
        assertEquals("down", resp.getBody().get("status"));
    }

    @Test
    @DisplayName("503 的响应体里仍然带着 admin_bootstrap —— 排障时最需要的就是它")
    void ready503StillCarriesAdminBootstrap() {
        setStatic(AdminBootstrap.class, "bootstrapStatus", "failed");
        setStatic(AdminBootstrap.class, "bootstrapError", "Field 'dept_id' doesn't have a default value");
        when(userRepository.selectCount(any())).thenThrow(new RuntimeException("down"));

        ResponseEntity<Map<String, Object>> resp = controller.readiness();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        assertEquals("failed", resp.getBody().get("admin_bootstrap"));
        assertNotNull(resp.getBody().get("admin_bootstrap_error"));
    }

    @Test
    @DisplayName("Redis 挂了但 MySQL 正常 ⇒ 降级而非摘流量（Redis 是共享依赖）")
    void readyDegradesWhenRedisDownButMySqlOk() {
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis unavailable"));

        ResponseEntity<Map<String, Object>> resp = controller.readiness();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("degraded", resp.getBody().get("status"));
    }

    @Test
    @DisplayName("/health/live 不碰任何外部依赖 —— 数据库抖一下不能导致全量重启")
    void liveNeverTouchesDependencies() {
        when(userRepository.selectCount(any())).thenThrow(new RuntimeException("MySQL down"));

        Map<String, Object> resp = controller.liveness();

        assertEquals("alive", resp.get("status"));
        // 探活不能因为 MySQL 挂了就失败，否则 K8s 会把所有副本一起重启
        assertTrue(resp.containsKey("uptime_sec"));
    }

    @Test
    @DisplayName("/health/components 的形状是 {overall, components:{...}}")
    void componentsHasOverallWrapper() {
        Map<String, Object> resp = controller.components();

        assertTrue(resp.containsKey("overall"));
        assertNotNull(resp.get("components"));
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) resp.get("components");
        assertTrue(components.containsKey("mysql"));
        assertTrue(components.containsKey("redis"));
        assertTrue(components.containsKey("rabbitmq"));
        assertTrue(components.containsKey("minio"));
        assertTrue(components.containsKey("vault"));
    }

    @Test
    @DisplayName("依赖卡住时探针不会跟着卡住 —— 超时即判 down")
    void slowDependencyDoesNotHangProbe() {
        // 模拟"连得上但不回数据"（网络分区 / 锁等待）：
        // 各客户端自己的超时是 30~60s，而 K8s readinessProbe 只等 1s。
        // 不设上限的话，探针会超时失败 ⇒ 所有 Pod 一起被摘出 Endpoints，
        // 把"某个共享依赖变慢"放大成"全站不可用"。
        when(userRepository.selectCount(any())).thenAnswer(inv -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 1L;
        });

        long start = System.currentTimeMillis();
        ResponseEntity<Map<String, Object>> resp = controller.readiness();
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 10_000,
                "探针不应被慢依赖拖住，实际耗时 " + elapsed + "ms");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
    }

    @Test
    @DisplayName("组件探测不把异常消息暴露到响应体里")
    void componentErrorsDoNotLeakDetails() {
        when(userRepository.selectCount(any()))
                .thenThrow(new RuntimeException("jdbc:mysql://db.internal:3306/edam?user=root"));

        ResponseEntity<Map<String, Object>> resp = controller.readiness();

        @SuppressWarnings("unchecked")
        Map<String, Object> mysql = (Map<String, Object>) resp.getBody().get("components") instanceof Map
                ? (Map<String, Object>) ((Map<String, Object>) resp.getBody().get("components")).get("mysql")
                : null;
        assertNotNull(mysql);
        assertEquals("down", mysql.get("status"));
        assertTrue(!mysql.toString().contains("jdbc:mysql"),
                "异常消息里通常含 JDBC URL / 主机名，不能出现在对外响应里");
    }

    // ------------------------------------------------------------------
    // 反射小工具
    // ------------------------------------------------------------------

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("反射设置字段失败: " + name, e);
        }
    }

    private static void setStatic(Class<?> type, String name, Object value) {
        try {
            Field f = type.getDeclaredField(name);
            f.setAccessible(true);
            f.set(null, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("反射设置静态字段失败: " + name, e);
        }
    }
}
