# 安全策略（Security Policy）

> 本文档描述 EDAM 项目的安全漏洞报告流程、支持版本范围、敏感数据保护策略。
> 最后更新：2026-08-12

## 支持的版本

下表列出 EDAM 项目当前获得安全更新支持的版本：

| 版本 | 支持状态 | 终止支持日期 |
| --- | --- | --- |
| 3.1.x | ✅ 活跃支持 | 2027-08-12 |
| 3.0.x | ✅ 安全修复 | 2027-02-12 |
| 2.0.x | ⚠️ 仅关键 CVE | 2026-12-12 |
| 1.0.x | ❌ 已停止 | 2026-08-12 |
| < 1.0 | ❌ 已停止 | 2026-08-12 |

**安全更新策略**：

- **当前主版本**（3.1.x）：所有 CVE 和 Bug 修复
- **上一个主版本**（3.0.x）：仅高危和关键 CVE
- **更早版本**：仅关键 CVE，且需提供商业升级路径

## 报告漏洞

我们非常重视安全问题。如果你发现了潜在的安全漏洞，请**负责任地披露**。

### 报告方式

**🔒 请通过以下私密渠道报告**：

| 渠道 | 联系方式 |
| --- | --- |
| **邮件** | `security@example.com` |
| **PGP 加密** | 公钥指纹 `EDAM-2026-SECURITY`（见下方）|
| **GitHub Security Advisories** | 仓库 → Security → Advisories → New draft |

**❌ 请勿**：
- 在公开 Issue 报告安全漏洞
- 在社交媒体或公开论坛讨论
- 在 PR 中暴露漏洞细节

### 报告内容

请尽可能提供以下信息：

```markdown
## 漏洞标题
[简短描述]

## 严重性评估
[ ] Critical - 远程代码执行 / 数据泄露
[ ] High - 权限提升 / 认证绕过
[ ] Medium - 信息泄露 / DoS
[ ] Low - 配置问题 / 弱加密

## 受影响版本
- 版本：v3.1.0
- 提交：abc123

## 漏洞详情
- 类型：（如 SQL 注入 / XSS / 越权 / 加密弱点）
- 攻击向量：（网络 / 本地 / 物理 / 社交工程）
- 前置条件：（需登录 / 需物理访问 / 无需认证）

## 复现步骤
1. ...
2. ...
3. ...

## 影响
- 数据泄露范围：（所有用户 / 特定用户 / 系统管理员）
- 业务影响：（机密性 / 完整性 / 可用性）

## 建议修复
- （可选）您的修复建议
```

### 我们的承诺

| 时间窗口 | 承诺 |
| --- | --- |
| **24 小时** | 确认收到报告 |
| **72 小时** | 初步评估严重性 |
| **7 天** | 提供临时缓解方案（如适用）|
| **30 天** | 修复 + 发布安全补丁（Critical/High）|
| **90 天** | 修复 + 发布安全补丁（Medium/Low）|

**披露政策**：漏洞修复后 90 天内公开披露，给用户足够时间升级。

## PGP 公钥

如需加密通信，请使用以下 PGP 公钥：

```
-----BEGIN PGP PUBLIC KEY BLOCK-----
Version: GnuPG v2

mQENBFxxxxxxx...

（公钥指纹：EDAM-2026-SECURITY-KEY-FINGERPRINT-AB12CD34）
-----END PGP PUBLIC KEY BLOCK-----
```

**注**：请将公钥上传至：
- `https://keys.openpgp.org/`
- 仓库 `.well-known/` 目录
- `https://example.com/security/pgp-key.asc`

## 安全最佳实践

### 部署侧

```yaml
# 强制 HTTPS
ingress:
  tls:
    - secretName: edam-tls
      hosts:
        - api.example.com

# 启用 NetworkPolicy 限制 Pod 间通信
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: edam-default-deny
spec:
  podSelector: {}
  policyTypes:
    - Ingress
    - Egress
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: edam-allow-internal
spec:
  podSelector:
    matchLabels:
      app: edam-backend
  ingress:
    - from:
        - podSelector:
            matchLabels:
              app: edam-nginx
      ports:
        - port: 8080

# Pod Security Standards
apiVersion: v1
kind: Namespace
metadata:
  name: edam
  labels:
    pod-security.kubernetes.io/enforce: restricted
    pod-security.kubernetes.io/audit: restricted
    pod-security.kubernetes.io/warn: restricted
```

### 应用侧

1. **密钥管理**：
   - 所有密钥存 Vault，**禁止**明文存储
   - JWT 签名密钥每 90 天轮转
   - 驱动加密密钥双密钥灰度

2. **访问控制**：
   - 强制 JWT 鉴权
   - 实施 RBAC + 资源级 ACL
   - 最小权限原则

3. **审计日志**：
   - 所有敏感操作记录（登录、上传、外发、密钥吊销）
   - 日志保留 365 天
   - 关键事件实时告警

4. **依赖安全**：
   - 订阅 CVE 通知
   - Snyk / Trivy 扫描
   - 季度升级窗口

### 用户侧

- 🔐 启用 MFA（多因素认证）
- 🔑 密码长度 ≥ 12 位，含大小写+数字+特殊字符
- 🚫 禁止共享账号
- 📱 离职员工及时吊销（24h 内）
- 🖥️ 终端安装最新驱动

## 安全合规

本项目遵循以下合规框架：

| 框架 | 状态 | 文档 |
| --- | --- | --- |
| **等保三级** | 设计中 | [第十三章 13.2 节](doc/) |
| **个保法（PIPL）** | 已对齐 | [第十三章 13.1 节](doc/) |
| **数据安全法** | 已对齐 | [第十三章 13.4 节](doc/) |
| **国密合规** | 算法就位 | [ADR-0006](docs/adr/0006-chinese-crypto.md) |
| **GDPR**（如适用）| 需评估 | 外部咨询 |
| **SOC 2** | 未认证 | 商业评估 |
| **ISO 27001** | 未认证 | 商业评估 |

## 已知安全限制

本项目在以下方面存在已知限制，建议在生产部署前评估：

1. **DWT 频域水印在视频重编码后会失效**（详见 [ADR-0004](docs/adr/0004-video-encryption.md)）
2. **HLS + AES-128 可被定向攻击者绕过**（不是 DRM）
3. **驱动签名需要 EV 代码证书**（生产部署需采购）
4. **未集成 Widevine / FairPlay DRM**（高敏感场景需评估）
5. **JWT 不携带 PII**（但前端单独 `/auth/me` 接口仍可能泄露）
6. **本项目未进行第三方安全审计**（建议商业部署前完成）

## 第三方组件安全

| 组件 | 漏洞追踪 | 升级窗口 |
| --- | --- | --- |
| Spring Boot 3.x | https://spring.io/security | 季度 |
| MySQL 8.0 | https://www.mysql.com/support/ | 半年 |
| Redis 7.x | https://github.com/redis/redis | 半年 |
| Nginx 1.25 | https://nginx.org/en/security_advisories.html | 紧急 |
| Vault | https://github.com/hashicorp/vault/security | 季度 |
| MinIO | https://github.com/minio/minio/security | 季度 |
| RabbitMQ | https://www.rabbitmq.com/security.html | 季度 |
| Bouncy Castle | https://github.com/bcgit/bc-java | 半年 |

## 漏洞奖励计划

**当前状态**：未启动

未来计划：建立漏洞奖励计划（Bug Bounty），覆盖：
- 认证绕过
- 越权访问
- 远程代码执行
- 数据泄露

预算：¥ 50,000 - ¥ 200,000 / 漏洞

## 联系方式

| 类型 | 邮箱 |
| --- | --- |
| **安全漏洞** | `security@example.com` |
| **一般问题** | `team@example.com` |
| **商务合作** | `business@example.com` |
| **媒体咨询** | `press@example.com` |

**紧急联系**（P0 漏洞）：`+86-xxx-xxxx-xxxx`（仅工作时间外紧急情况）

## 致谢

我们感谢以下安全研究员的负责任披露（按时间倒序）：

- （暂无）

如果你提交了有效的漏洞报告并希望被致谢，请在报告中注明。

---

**最后更新**：2026-08-12
**下次审查**：2026-11-12（每季度审查）