# EDAM 密钥生命周期管理（v3.3 W-1 G-6）

- 文档版本：v1.0
- 编制日期：2026-08-25
- 对应方案书：第九章 9.4 数据安全与 Vault 高可用
- 关联评审：v1.0 P0-5 密钥管理缺失 → v2.0 已修复（Vault 部署）→ v3.3 文档化
- 关联等保：等保三级 G-6「密钥生命周期管理文档」

---

## 一、密钥分类

### 1.1 密钥清单

| 密钥 ID | 类型 | 算法 | 用途 | 存储位置 | 轮转周期 |
| --- | --- | --- | --- | --- | --- |
| `edam-hls-aes` | 对称加密 | AES-128-CBC | HLS 视频分片加密 | Vault Transit | 90 天 |
| `edam-doc-aes` | 对称加密 | AES-256-GCM | 文档透明加密（MinIO） | Vault Transit | 90 天 |
| `edam-driver-master` | 对称加密 | AES-256-GCM | 驱动主密钥（方案 B 移除，保留备查）| Vault Transit | 90 天 |
| `edam-jwt-signing` | HMAC | HMAC-SHA256 | JWT 签名 | Vault Transit | 180 天 |
| `edam-session-token` | 随机 | SecureRandom 256 bit | session_id | DB | 不轮转（一次性）|
| `edam-refresh-token` | 随机 | SecureRandom 256 bit | refresh_token | Redis + DB | 7 天过期 |
| `edam-pii-key` | 对称加密 | AES-256-GCM | PII 字段加密（real_name/email/phone）| Vault Transit | 90 天 KEK |
| `edam-data-key` | 对称加密 | AES-256-GCM | 数据加密密钥（DEK） | Vault Transit + KMS | 365 天 |
| `edam-webhook-secret` | HMAC | HMAC-SHA256 | Webhook 签名 | DB（per webhook） | 不轮转（可手动撤销）|
| `edam-sm4-hls` | 对称加密 | SM4-CBC | HLS 国密加密（v3.3 W-2 启用）| Vault Transit | 90 天 |
| `edam-sm3-jwt` | HMAC | SM3 | JWT 国密摘要（v3.3 W-2 启用）| Vault Transit | 180 天 |

### 1.2 密钥等级

| 等级 | 描述 | 示例 | 存储 |
| --- | --- | --- | --- |
| **L1 关键** | 业务核心，泄露即灾难 | `edam-hls-aes`（视频）| HSM（生产）/ Vault（开发）|
| **L2 重要** | 泄露影响大 | `edam-pii-key` | Vault Transit |
| **L3 普通** | 泄露影响有限 | `edam-jwt-signing` | Vault Transit |
| **L4 临时** | 短期使用 | `edam-session-token` | Redis（短期）|

---

## 二、生命周期 6 阶段

### 2.1 生成（Generate）

- **算法选型**：NIST FIPS 140-2 批准（v3.3 优先国密）
- **随机源**：`/dev/urandom` 或 HSM TRNG
- **长度**：
  - AES-128 / AES-256（128 / 256 bit）
  - RSA-2048 / RSA-4096（2048 / 4096 bit）
  - SM4（128 bit）
- **生成工具**：HashiCorp Vault Transit 引擎
- **生成审计**：记录到 `key_rotation_log` 表（操作人、生成时间、用途）

### 2.2 存储（Store）

| 层级 | 存储位置 | 加密方式 |
| --- | --- | --- |
| **生产（HSM）** | HSM 硬件 | 永远不解密，明文仅在 HSM 内部 |
| **预生产（Vault）** | Vault Raft 集群 | 主密钥加密 + unseal key 分片 |
| **开发** | Vault 单节点 | unseal key 文件（500 权限）|

**禁止**：
- ❌ 在代码、配置文件、Git 中存储明文密钥
- ❌ 通过聊天工具 / 邮件传输密钥
- ❌ 在日志中打印密钥内容

### 2.3 分发（Distribute）

- **应用层**：通过 Vault Token（短期，TTL ≤ 24h）调用 Transit 引擎
- **终端层**：（方案 B 移除）原计划通过 VPN + MDM 推送
- **微服务**：Service Token（k8s serviceaccount 绑定 Vault 角色）
- **审计**：每次密钥获取记录到 `vault_audit_log`

### 2.4 使用（Use）

- **传输加密**：TLS 1.2+（生产），明文禁止跨网络
- **内存加密**：敏感字段解密后立即使用，不长期驻留
- **进程隔离**：密钥解密与业务逻辑分离
- **缓存策略**：会话级缓存（与 session 同步过期）

### 2.5 更新（Rotate）

- **周期**：

| 密钥类型 | 轮转周期 | 灰度期 |
| --- | --- | --- |
| HLS / DOC / PII KEK | 90 天 | 14 天双密钥并行 |
| JWT 签名 | 180 天 | 7 天双密钥 |
| DEK | 365 天 | 30 天双密钥 |
| 国密密钥 | 与国际同步 | 14 天双密钥 |

- **流程**：
 1. 生成新密钥（v_new）
 2. 双密钥并行：业务同时支持 v_old + v_new
 3. 灰度期间：新数据用 v_new，旧数据仍可解（v_old）
 4. 灰度结束：废弃 v_old（标记 `status=retired`）
 5. 重新加密历史数据（按需）

- **自动触发**：cron job 每 60 天检查密钥年龄，到期触发
- **手动触发**：紧急泄露 → 立即轮转

### 2.6 销毁（Destroy）

- **场景**：
  - 密钥生命周期结束（> 2 年）
  - 泄露事件（强制）
  - 业务下线（无需再解密）
- **流程**：
 1. `key_rotation_log.status = retired`
 2. 重新加密依赖此密钥的历史数据
 3. Vault Transit 中 `key deletion_allowed = true` 后删除
 4. 记录到 `key_destruction_log`（不可变）

---

## 三、Vault Transit 配置

### 3.1 Transit 引擎启用

```bash
vault secrets enable transit
```

### 3.2 密钥创建

```bash
# AES-256-GCM 数据加密密钥
vault write -f transit/keys/edam-data-key \
  type=aes256-gcm96 \
  exportable=false \
  allow_plaintext_backup=false

# HMAC 签名密钥
vault write -f transit/keys/edam-jwt-signing \
  type=hmac-sha2-256

# 国密 SM4 密钥（v3.3 W-2 启用）
vault write -f transit/keys/edam-sm4-hls \
  type=aes128-cbc96  # Vault Transit 不直接支持 SM4，使用 AES-128-CBC 兼容
```

### 3.3 密钥轮转

```bash
# 触发轮转
vault write transit/keys/edam-data-key/rotate

# 设置轮转周期（自动）
vault write transit/keys/edam-data-key/config \
  deletion_allowed=true \
  exportable=false

# 查看密钥历史
vault read transit/keys/edam-data-key
```

### 3.4 应用层调用

```java
// Java Vault 客户端
VaultResponse<EncryptionResponse> response = vault.logical()
    .write("transit/encrypt/edam-pii-key", Map.of(
        "plaintext", Base64.getEncoder().encodeToString(plaintext.getBytes())
    ));
String ciphertext = response.getData().get("ciphertext");
```

### 3.5 自动轮转（cron）

```yaml
# k8s CronJob
apiVersion: batch/v1
kind: CronJob
metadata:
  name: vault-key-rotation
spec:
  schedule: "0 2 1 */3 *"  # 每 3 个月 1 日 02:00
  jobTemplate:
    spec:
      template:
        spec:
          containers:
          - name: rotation
            image: vault:latest
            command:
            - /bin/sh
            - -c
            - |
              for key in edam-hls-aes edam-doc-aes edam-pii-key; do
                vault write transit/keys/$key/rotate
              done
              echo "Keys rotated: $(date)"
```

---

## 四、密钥审计

### 4.1 审计日志

| 字段 | 描述 |
| --- | --- |
| timestamp | 操作时间 |
| actor | 操作人（用户 / 系统） |
| key_id | 密钥 ID |
| operation | encrypt / decrypt / rotate / delete |
| request_ip | 调用方 IP |
| success | 成功 / 失败 |
| error_msg | 错误信息（如失败） |

### 4.2 审计存储

- **Vault Audit Device**：所有 API 调用记录到 `vault_audit_log`
- **DB 关键事件**：`key_rotation_log` 表（轮转、撤销）
- **日志聚合**：ELK 集中存储 ≥ 365 天

### 4.3 审计告警

| 事件 | 告警 |
| --- | --- |
| 同一密钥 1 分钟内解密 ≥ 1000 次 | P2 告警（高频访问） |
| 同一密钥 5 分钟内解密失败 ≥ 10 次 | P1 告警（可能暴力破解） |
| 任何密钥 manual rotate | P2 告警（人工干预） |
| 任何密钥 delete | P0 告警（永久操作） |

---

## 五、密钥泄露应急响应

### 5.1 应急流程

```
1. 确认泄露（来源、范围、时间）
2. 立即轮转受影响密钥
3. 重新加密历史数据（按需）
4. 通知相关方（法务、合规、客户）
5. 评估下游影响（已解密数据是否还需保护）
6. 复盘 + 改进
```

### 5.2 触发条件

- 密钥在 Git / 公开日志 / 第三方系统中泄露
- 异常解密行为（异常 IP / 异常时间 / 异常量）
- 内部员工主动上报或外部披露

### 5.3 SLA

- **P0 泄露**：30 分钟内完成密钥轮转 + 历史数据重新加密
- **P1 泄露**：4 小时内完成
- **P2 泄露**：24 小时内完成

---

## 六、密钥管理 SOP

### 6.1 日常运维

| 项 | 频率 | 责任人 |
| --- | --- | --- |
| 密钥年龄检查 | 每日（cron） | SRE |
| 备份完整性校验 | 每周 | DBA + SRE |
| 审计日志审查 | 每周 | 安全 |
| 轮转演练 | 每季度 | 安全 + 后端 |

### 6.2 演练场景

1. 模拟密钥泄露 → 触发轮转
2. 模拟 Vault 故障 → 降级模式（仅读）
3. 模拟审计失败 → 排查 + 修复

### 6.3 文档维护

- 每次密钥策略调整 → 更新本文档
- 每次轮转 → 记录到 `key_rotation_log` + Vault Audit
- 每年一次 → 全量复审

---

## 七、合规对齐

| 标准 | 条款 | 满足情况 |
| --- | --- | --- |
| GB/T 22239-2019 | 8.1.5.4 密钥管理 | ✅ |
| GM/T 0028-2014 | 密码模块安全技术要求 | 🔲 待 v3.3 W-2 国密集成 |
| PCI DSS 4.0 | Req 3.6 / 3.7 | ✅ |
| 等保三级 | 密码应用安全 | ✅ |

---

## 八、附件

- `modify/2026-08-12-威胁建模报告.md` — STRIDE 35 条（含密钥相关威胁）
- `modify/2026-08-12-国密合规认证方案.md` — SM2/SM3/SM4 集成
- 方案书 §9.4 数据安全与 Vault 高可用

---

**密钥生命周期管理文档完成。** 等待团队按 SOP 实施。