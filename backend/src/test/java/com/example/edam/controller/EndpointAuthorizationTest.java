package com.example.edam.controller;

import com.example.edam.security.webauthn.WebAuthnController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 端点授权的**结构性**回归测试。
 *
 * <p><b>要防的是什么</b>：2026-11-07 审计发现
 * {@code GET /audit/logs}、{@code GET /users}、{@code GET /admin/backups}、
 * {@code GET /rbac/users/{id}/permissions} 这四个端点**没有 {@code @PreAuthorize}** ——
 * 前端菜单按 {@code audit:read} 过滤了「审计日志」入口，后端却没人管，
 * 任何登录用户绕过菜单直接调 API 就能拿到全公司审计日志 / 员工名册。
 *
 * <p>这类洞的特点是：**编译不报错、测试不报警、页面看起来正常**。
 * 前端菜单过滤让人以为"后端也有门"，而权限是数据不是代码 ——
 * 少写一个注解，不会有人发现。
 *
 * <p><b>本测试的规则</b>：每个 {@code @RestController} 端点，
 * 要么自己带 {@code @PreAuthorize}，要么它的类带，
 * 要么它出现在 {@link #EXCEPTIONS} 里并写明"为什么这里故意不设门"。
 * 新加端点忘了设门 ⇒ 这里红。
 *
 * <p>注意 {@code @PreAuthorize} 在方法上和类上都算 —— 类级注解覆盖类里所有端点
 * （{@code ClassificationController} 就是这么做的）。
 *
 * <p>方案书 5.4 细粒度权限与外发管控。
 */
class EndpointAuthorizationTest {

    /** 端点签名 → 「故意不设门」的原因。 */
    private static final Map<String, String> EXCEPTIONS = buildExceptions();

    /** 关键端点 → 必须出现的权限码片段（防止门被悄悄改弱）。 */
    private static final Map<String, String> REQUIRED_GATES = Map.of(
        "AuditController#list",                "audit:read",
        "AuditController#getById",             "audit:read",
        "UserController#list",                 "user:read",
        "UserController#getById",              "user:read",
        "AdminController#listBackups",         "admin:backup",
        "RbacController#getUserPermissions",   "user:read",
        "DistributionController#decide",       "distribution:approve",
        "DocumentController#upload",           "document:upload",
        "VideoController#upload",              "video:upload"
    );

    @Test
    @DisplayName("每个端点要么有 @PreAuthorize，要么在例外清单里写明原因")
    void everyEndpointIsEitherGatedOrDocumented() {
        Map<String, String> ungated = new TreeMap<>();

        for (Class<?> controller : scanControllers()) {
            String classPath = classPathOf(controller);
            String classGate = gateOf(controller);

            for (Method method : controller.getDeclaredMethods()) {
                String mapping = mappingOf(method);
                if (mapping == null) {
                    continue;
                }
                String method2 = mapping.substring(0, mapping.indexOf(' '));
                String path = mapping.substring(mapping.indexOf(' ') + 1);

                if (gateOf(method) != null || classGate != null) {
                    continue;
                }
                String key = method2 + " " + path;
                if (!EXCEPTIONS.containsKey(key)) {
                    ungated.put(key, controller.getSimpleName() + "#" + method.getName());
                }
            }
        }

        if (!ungated.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("以下端点既没有 @PreAuthorize，也不在例外清单里 —— ")
              .append("任何登录用户都能直接调用它们：\n");
            ungated.forEach((k, v) -> sb.append("  - ").append(k).append("   (").append(v).append(")\n"));
            sb.append("要么给它们加 @PreAuthorize，要么在 EndpointAuthorizationTest.EXCEPTIONS 里")
              .append("补上原因 —— 理由必须是「这里靠什么鉴权」，不能是「忘了」。\n");
            fail(sb.toString());
        }
    }

    @Test
    @DisplayName("关键端点的权限码没被改弱")
    void criticalGatesAreIntact() {
        Map<String, Method> targets = new LinkedHashMap<>();
        for (Class<?> controller : scanControllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                targets.put(controller.getSimpleName() + "#" + method.getName(), method);
            }
        }

        REQUIRED_GATES.forEach((key, required) -> {
            Method method = targets.get(key);
            assertTrue(method != null, "找不到方法：" + key);
            String gate = gateOf(method);
            if (gate == null) {
                gate = gateOf(method.getDeclaringClass());
            }
            assertTrue(gate != null, key + " 少了 @PreAuthorize");
            assertTrue(gate.contains(required),
                key + " 的门应当包含 '" + required + "'，实际是：" + gate);
        });
    }

    @Test
    @DisplayName("例外清单里的端点都真实存在（清单不能有僵尸条目）")
    void exceptionListHasNoStaleEntries() {
        Set<String> actual = new java.util.HashSet<>();
        for (Class<?> controller : scanControllers()) {
            String classPath = classPathOf(controller);
            String classGate = gateOf(controller);
            for (Method method : controller.getDeclaredMethods()) {
                String mapping = mappingOf(method);
                if (mapping == null) {
                    continue;
                }
                String verb = mapping.substring(0, mapping.indexOf(' '));
                String path = mapping.substring(mapping.indexOf(' ') + 1);
                if (gateOf(method) == null && classGate == null) {
                    actual.add(verb + " " + path);
                }
            }
        }

        List<String> stale = new ArrayList<>();
        for (String key : EXCEPTIONS.keySet()) {
            if (!actual.contains(key)) {
                stale.add(key);
            }
        }
        assertTrue(stale.isEmpty(),
            "例外清单里有已经不存在的端点（端点被删或改成了有门）：" + stale);
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static List<Class<?>> scanControllers() {
        List<Class<?>> out = new ArrayList<>();
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (String pkg : List.of("com.example.edam.controller", "com.example.edam.security.webauthn")) {
            for (BeanDefinition def : scanner.findCandidateComponents(pkg)) {
                try {
                    out.add(Class.forName(def.getBeanClassName()));
                } catch (ClassNotFoundException ignored) {
                    // 理论上到不了这里；扫不到的类不会造成漏判（漏判的是"多门"不是"少门"）
                }
            }
        }
        // WebAuthnController 已被上面的扫描覆盖；这里只是确保它一定在集合里，
        // 避免扫描器因 @RestController 元注解解析差异把它漏掉。
        if (!out.contains(WebAuthnController.class)) {
            out.add(WebAuthnController.class);
        }
        return out;
    }

    /** 类级 {@code @RequestMapping} 前缀（没有则空串）。 */
    private static String classPathOf(Class<?> controller) {
        RequestMapping rm = AnnotationUtils.findAnnotation(controller, RequestMapping.class);
        if (rm == null || rm.value().length == 0) {
            return "";
        }
        return trimSlash(rm.value()[0]);
    }

    /** 拼出 "METHOD /path"（path 一定以 / 开头，结尾不带 /）。 */
    private static String joinPath(String classPath, String sub) {
        String s = trimSlash(sub);
        String full;
        if (classPath.isEmpty()) {
            full = s.isEmpty() ? "/" : "/" + s;
        } else {
            full = s.isEmpty() ? "/" + classPath : "/" + classPath + "/" + s;
        }
        // Spring 允许在路径变量里带正则：{filename:.+} → 规范里记作 {filename}
        // （与 scripts/sync_openapi_permissions.py 的 NORM_RE 一致）
        return full.replaceAll("\\{([^}:]+):[^}]+\\}", "{$1}");
    }

    /** 返回 "METHOD /path"，不是映射端点则返回 null。 */
    private static String mappingOf(Method method) {
        for (Class<? extends Annotation> type : List.of(
                GetMapping.class, PostMapping.class, PutMapping.class,
                DeleteMapping.class, PatchMapping.class)) {
            Annotation ann = AnnotationUtils.findAnnotation(method, type);
            if (ann == null) {
                continue;
            }
            String[] values = (String[]) AnnotationUtils.getValue(ann, "value");
            String sub = (values != null && values.length > 0) ? values[0] : "";
            String verb = type.getSimpleName().replace("Mapping", "").toUpperCase();
            return verb + " " + joinPath(classPathOf(method.getDeclaringClass()), sub);
        }
        return null;
    }

    /** 方法级或类级的 {@code @PreAuthorize} 表达式，没有则 null。 */
    private static String gateOf(Method method) {
        PreAuthorize pa = AnnotationUtils.findAnnotation(method, PreAuthorize.class);
        return pa != null ? pa.value() : null;
    }

    private static String gateOf(Class<?> clazz) {
        PreAuthorize pa = AnnotationUtils.findAnnotation(clazz, PreAuthorize.class);
        return pa != null ? pa.value() : null;
    }

    private static String trimSlash(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        while (t.startsWith("/")) {
            t = t.substring(1);
        }
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    /**
     * 故意不设 {@code @PreAuthorize} 的端点清单。
     *
     * <p>每一条都必须写明「这里靠什么鉴权」——
     * 靠别的机制（token scope / ACL / 匿名 / 探针）是可以的，
     * 「忘了」不行。
     */
    private static Map<String, String> buildExceptions() {
        Map<String, String> m = new LinkedHashMap<>();

        // —— 匿名入口：SecurityConfig 里 permitAll ——
        m.put("POST /auth/login",                    "匿名入口（SecurityConfig permitAll）");
        m.put("POST /auth/refresh",                  "匿名入口（凭 refresh_token 换新 token）");
        m.put("POST /auth/sso/exchange",             "匿名入口（SSO 回调换 token）");
        m.put("POST /auth/webauthn/login/begin",     "匿名入口（拿 challenge，不涉及身份）");
        m.put("POST /auth/webauthn/login/complete",  "匿名入口（WebAuthn 断言换 token）");

        // —— 自助服务：只作用于调用者自己的账号 ——
        m.put("GET /auth/me",                        "自助服务（只返回当前登录用户自己）");
        m.put("PUT /auth/profile",                   "自助服务（只能改自己）");
        m.put("POST /auth/change-password",          "自助服务（改自己的密码，需校验旧密码）");
        m.put("POST /auth/logout",                   "自助服务（注销自己的会话）");
        m.put("GET /auth/webauthn/credentials",       "自助服务（列出自己的凭证）");
        m.put("DELETE /auth/webauthn/credentials/{credentialId}", "自助服务（删自己的凭证）");
        m.put("POST /auth/webauthn/register/begin",  "自助服务（注册自己的 WebAuthn 凭证）");
        m.put("POST /auth/webauthn/register/complete","自助服务（完成自己的 WebAuthn 注册）");

        // —— K8s 探针：匿名可达是设计 ——
        m.put("GET /health",                         "探针（匿名可达是设计）");
        m.put("GET /health/live",                    "探针（匿名可达是设计）");
        m.put("GET /health/ready",                   "探针（匿名可达是设计）");
        m.put("GET /health/components",              "探针（匿名可达是设计）");

        // —— 播放链路：用 query token + scope=playback 鉴权，不是登录态 ——
        m.put("GET /playback/{video_id}/playlist.m3u8", "播放链路（query token + scope=playback）");
        m.put("GET /playback/{video_id}/segment/{filename}", "播放链路（query token + scope=playback）");
        m.put("GET /playback/{video_id}/key",        "播放链路（query token + scope=playback）");
        m.put("POST /playback/{video_id}/log",       "播放埋点（按 session_id 校验）");

        // —— Worker 回调：靠 WorkerTokenVerifier，不是登录态 ——
        m.put("PATCH /documents/{doc_id}/status",    "Worker 回调（WorkerTokenVerifier）");
        m.put("PATCH /videos/{video_id}/status",     "Worker 回调（WorkerTokenVerifier）");

        // —— 方案书 5.6.1【例外】：下载权由密级 + ACL 判定，不走角色门 ——
        m.put("GET /preview/{doc_id}/download",      "方案书 5.6.1 例外（下载由密级/ACL 判定）");

        // —— 读接口由资源级 ACL（密级 + 显式授权）判定可见性，不走角色门 ——
        //     这是"两层权限"的设计：RBAC 管写，ACL 管读。
        m.put("GET /videos",                         "读接口，可见性由资源级 ACL 判定");
        m.put("GET /videos/{video_id}",              "读接口，可见性由资源级 ACL 判定");
        m.put("GET /v2/videos",                      "读接口，可见性由资源级 ACL 判定");
        m.put("GET /documents",                      "读接口，可见性由资源级 ACL 判定");
        m.put("GET /documents/{doc_id}",             "读接口，可见性由资源级 ACL 判定");
        m.put("GET /documents/search",               "读接口，可见性由资源级 ACL 判定");
        m.put("GET /search/videos",                  "读接口，可见性由资源级 ACL 判定");
        m.put("GET /search/documents",               "读接口，可见性由资源级 ACL 判定");
        m.put("GET /tags",                           "读接口，可见性由资源级 ACL 判定");
        m.put("GET /tags/{tag_id}",                  "读接口，可见性由资源级 ACL 判定");
        m.put("GET /dashboard/stats",                "读接口，可见性由资源级 ACL 判定");
        m.put("GET /watermarks/cache",               "读接口，可见性由资源级 ACL 判定");

        // —— 通知：只读写调用者自己的通知 ——
        m.put("GET /notifications",                  "只读调用者自己的通知");
        m.put("GET /notifications/preferences",      "只读调用者自己的通知");
        m.put("PUT /notifications/preferences",      "只读写调用者自己的通知");
        m.put("POST /notifications/{notif_id}/read",  "只读写调用者自己的通知");
        m.put("POST /notifications/read-all",         "只读写调用者自己的通知");
        m.put("GET /ws/notifications",               "只推调用者自己的通知");

        return m;
    }
}
