package com.example.edam.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Flyway 迁移前置守卫。
 *
 * <p><b>要防什么</b>：本项目历史上删除过迁移文件（backend 的 V1/V2/V3 被
 * 统一到 V2026* 系列）。但 Maven 的 resources 插件**只复制/覆盖**，
 * **不删除**源里已移除的资源 —— 于是
 * {@code backend/target/classes/db/migration/} 里会残留
 * {@code V1__init_schema.sql} 等废弃脚本。
 *
 * <p>后果：Flyway 会同时看到两套迁移（V1/V2/V3 + V2026*），
 * V3 建的 {@code sys_role_permission} 是**旧结构**，
 * 而 V20260812_1000 里的 {@code CREATE TABLE IF NOT EXISTS} 会静默跳过
 * 它 → 最后在加外键约束时报
 * {@code Error 3780: ... are incompatible}。
 * 报错位置（init 脚本第 500+ 行）离病因（target 目录有陈旧产物）
 * 极远，排查一次要花很久。
 *
 * <p>所以：迁移开始前先扫一遍 classpath，发现**已知废弃**的迁移文件
 * 就直接失败，并明确告诉运维执行 {@code mvn clean}。
 */
@Configuration
public class FlywayMigrationGuard {

    private static final Logger log = LoggerFactory.getLogger(FlywayMigrationGuard.class);

    /**
     * 已从源码删除、但可能残留在 target/classes 里的旧迁移。
     *
     * <p>只列"确定废弃"的 —— 误报会导致正常环境起不来。
     */
    private static final List<String> DEPRECATED_MIGRATIONS = Arrays.asList(
        "V1__init_schema.sql",
        "V2__widen_mime_type.sql",
        "V3__rbac_complete.sql"
    );

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            List<String> stale = findStaleMigrations();
            if (!stale.isEmpty()) {
                throw new IllegalStateException(
                    "检测到 classpath 上存在**已废弃的迁移脚本**："
                        + String.join(", ", stale)
                        + "。"
                        + "这些是历史 schema 的残留（backend/target/classes/db/migration/ 下"
                        + "Maven 编译产物没被清理）。"
                        + "它们会让 Flyway 同时加载两套 schema，导致外键约束报 "
                        + "'are incompatible'（Error 3780）。"
                        + "请先执行 `mvn clean`（或删除 backend/target 目录）再重新启动。"
                );
            }
            flyway.migrate();
        };
    }

    /**
     * 扫描 classpath 上的 db/migration/，找出已废弃的迁移文件。
     */
    private List<String> findStaleMigrations() {
        List<String> found = new ArrayList<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:db/migration/*.sql");
            for (Resource r : resources) {
                String name = r.getFilename();
                if (name != null && DEPRECATED_MIGRATIONS.contains(name)) {
                    found.add(name);
                }
            }
        } catch (IOException e) {
            // 扫不到不算致命 —— 让 Flyway 自己报错，别在这里挡路
            log.warn("stale_migration_scan_failed: {}", e.getMessage());
        }
        if (!found.isEmpty()) {
            log.error("stale_migrations_detected: {}", found);
        }
        return found;
    }
}
