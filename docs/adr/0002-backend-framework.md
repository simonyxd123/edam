# ADR-0002：后端框架选型（Spring Boot 3.x vs Quarkus vs Micronaut）

- **状态**：✅ 已接受
- **日期**：2026-08-12

## 上下文

业务后端需要支撑：
- 30+ REST API（鉴权、资源、播放、审批、审计）
- 高并发（5000 路视频并发）
- 复杂的业务逻辑（RBAC、审批流、水印触发）
- 与 Spring 生态深度整合（Spring Security、Spring Data、Spring Cloud）

## 评估

| 维度 | Spring Boot 3.x | Quarkus | Micronaut |
| --- | --- | --- | --- |
| 启动时间 | 2-3s | 0.5-1s | 0.5-1s |
| 内存占用 | 200-300MB | 50-100MB | 80-120MB |
| 社区生态 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ |
| 学习曲线 | 低（团队熟悉） | 中 | 中 |
| AOT 原生镜像 | 实验性 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ |
| 启动时间对业务影响 | 弱（K8s 滚动更新） | 强 | 强 |
| 团队现有技能匹配 | ⭐⭐⭐⭐⭐ | ⭐ | ⭐ |

## 决策

**采用 Spring Boot 3.x**。

理由：
1. **团队技能匹配**：现有开发团队全部为 Java/Spring 技术栈，切换框架的培训成本高
2. **生态成熟**：Spring Security / Spring Data JPA / Spring Cloud 生态完善
3. **AOT 不是刚需**：K8s 滚动更新 + Pod 预热足够，2-3s 启动时间不构成瓶颈
4. **社区与招聘**：Spring 开发者市场充足，长期维护成本低

Quarkus/Micronaut 的"启动快 + 内存低"对 Serverless 场景更有价值，但本系统部署在 K8s 长连接 Pod 中，收益有限。

## 后果

- **正向**：团队技能 100% 复用；社区资源丰富；技术债低
- **负向**：相比 Quarkus 内存占用高 3-5 倍；冷启动较慢
- **缓解**：使用 GraalVM Native Image 实验性支持（v3.2 评估）