# Vault Transit 国密双轨集成（v3.3 W-2.5）

- 文档版本：v1.0
- 编制日期：2026-08-25
- 关联：v3.3 W-2.5 + W-3 商密使用许可申请
- 关联文档：`docs/key-management.md`

---

## 一、Vault Transit 双轨设计

### 1.1 双轨架构

```
┌────────────────────────────────────────────────────────────────┐
│                     应用层（Java / Python）                    │
│                                                                │
│   AlgorithmRouter.selectSymmetric(classification_lv)           │
│     - L1/L2: AES-256-GCM（国际）                              │
│     - L3/L4: SM4-GCM（国密）                                  │
└────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌────────────────────────────────────────────────────────────────┐
│                  Vault Transit 双轨引擎                        │
│                                                                │
│   ┌──────────────┐    ┌──────────────┐                       │
│   │ 国际密钥      │    │ 国密密钥      │                       │
│   │ edam-pii-key │    │ edam-pii-sm  │                       │
│   │ AES-256-GCM  │    │ SM4-GCM*     │                       │
│   └──────────────┘    └──────────────┘                       │
│         ▲                       ▲                             │
│         │ 双密钥并行（14 天灰度）                              │
│         ▼                                                     │
│   HSM（生产）/ Vault（开发）                                   │
└────────────────────────────────────────────────────────────────┘
```

*注：Vault 原生 Transit 引擎目前仅支持 AES-256-GCM、ECDSA、RSA 等国际算法。SM4-GCM 需要自定义插件或调用外部国密 SDK。

### 1.2 实施路径

| 阶段 | 状态 | 说明 |
| --- | --- | --- |
| **过渡期（v3.3 W-2.1）** | ✅ 当前 | BouncyCastle 在应用层实现 SM4/SM3/SM2；Vault 仅存 AES-256-GCM |
| **双轨期（v3.3 W-2.5）** | 🔄 当前文档 | Vault 存 AES-256-GCM，应用层在 L3+ 强制 SM4 双轨加密（应用层封装）|
| **原生集成（v3.4+）** | 🔲 未来 | Vault Transit SM4 自定义插件（HashiCorp 计划支持）或商用国密 SDK |

---

## 二、应用层双轨实现

### 2.1 加密策略

```java
public byte[] encryptPII(String plaintext, String classificationLv) {
    AlgorithmRouter.Algorithm algo = algorithmRouter.selectSymmetric(classificationLv);
    
    // 1. 从 Vault 拉取密钥
    byte[] key = vault.read("transit/keys/edam-pii-key");
    byte[] iv = generateIv();
    
    // 2. 按算法加密
    byte[] ciphertext = algorithmRouter.encrypt(algo, plaintext.getBytes(), key, iv);
    
    // 3. 拼接：version(1) + algo(1) + iv(16) + ciphertext
    return pack(algo, iv, ciphertext);
}
```

### 2.2 解密策略

```java
public String decryptPII(byte[] packed, String classificationLv) {
    // 1. 解析：version + algo + iv + ciphertext
    var parts = unpack(packed);
    
    // 2. 按算法解密（不强制使用 classification_lv，因为已加密）
    byte[] plaintext = algorithmRouter.decrypt(parts.algo, parts.ciphertext, parts.key, parts.iv);
    
    return new String(plaintext);
}
```

### 2.3 密钥轮转策略

```
Day 0:    AES-256 (v1) active
Day 75:   AES-256 (v2) created, 双密钥并行
Day 89:   AES-256 (v1) retired, AES-256 (v2) active
Day 90:   AES-256 (v1) deleted
```

**国密密钥同步**：
- L3+ 数据：SM4-GCM 密钥单独管理（90 天周期与 AES 错开）
- 双轨：加密时同时生成 v1 (AES) + v2 (SM4) 密文，存两份
- 解密：按密文算法版本自动选择

---

## 三、Vault Transit 配置

### 3.1 国际算法密钥（启用）

```bash
# 启用 Transit 引擎
vault secrets enable transit

# PII 数据加密密钥（AES-256-GCM）
vault write -f transit/keys/edam-pii-key \
  type=aes256-gcm96 \
  exportable=false \
  allow_plaintext_backup=false

# 文档加密密钥
vault write -f transit/keys/edam-doc-key \
  type=aes256-gcm96

# JWT 签名密钥
vault write -f transit/keys/edam-jwt-key \
  type=ed25519

# HLS 视频加密密钥
vault write -f transit/keys/edam-hls-key \
  type=aes256-gcm96
```

### 3.2 国密算法密钥（应用层管理 + Vault 存密文）

由于 Vault Transit 不直接支持 SM4，国密密钥走两种路径：

**路径 A：Vault 存国密密钥二进制**

```bash
# Vault KV v2 存国密密钥（密文）
vault kv put secret/gmsm/edam-pii-sm4 key="<base64-encoded-sm4-key>" \
  classification_lv="L3"

# 应用层读取
byte[] sm4Key = Base64.decode(vault.kv.read("secret/gmsm/edam-pii-sm4").getData().get("key"));
```

**路径 B：Vault Transit 自定义插件（v3.4+ 路线图）**

需要使用 HashiCorp Vault 自定义插件机制（Go 语言）封装 SM4 算法，并通过 Vault 插件目录挂载。

### 3.3 双轨密钥轮转

```python
def rotate_keys():
    """双密钥轮转脚本"""
    # 1. AES 密钥轮转（Vault 自动）
    vault.write('transit/keys/edam-pii-key/rotate')

    # 2. SM4 密钥轮转（应用层）
    new_sm4_key = generate_sm4_key()
    old_sm4_key = get_sm4_key()

    # 3. 灰度发布（14 天双密钥并行）
    save_sm4_key('new', new_sm4_key)
    save_sm4_key('legacy', old_sm4_key)
    print('SM4 dual-key rotation in progress for 14 days...')

    # 4. 14 天后废弃旧密钥
    schedule_call(14_days_later, lambda: delete_sm4_key('legacy'))
```

---

## 四、双轨数据迁移

### 4.1 历史数据迁移（一次性）

```sql
-- 1. 查询需要迁移的 L3+ PII 数据
SELECT id, real_name, email, phone FROM sys_user WHERE classification_lv IN ('L3', 'L4');

-- 2. 应用层批量重新加密
-- 加密格式：version(1) + algo(1) + iv(16) + ciphertext
UPDATE sys_user
SET real_name = RE_ENCRYPTED_NAME(real_name, 'SM4-GCM'),
    email = RE_ENCRYPTED_EMAIL(email, 'SM4-GCM')
WHERE classification_lv IN ('L3', 'L4');
```

### 4.2 灰度切换（14 天）

| 阶段 | 新数据 | 旧数据 |
| --- | --- | --- |
| Day 0-7 | SM4-GCM（100%） | AES-256-GCM（保留可读）|
| Day 8-14 | SM4-GCM（100%） | AES-256-GCM（保留可读）|
| Day 15+ | SM4-GCM（100%） | 已迁移或退役 |

### 4.3 回滚预案

如果 SM4 算法发现问题（生产事故），可一键回滚到 AES：

```bash
# 1. 切换配置
vault.kv.put secret/gmsm/active=disabled

# 2. 应用层自动 fallback
if vault.kv.read('secret/gmsm/active') == 'disabled':
    use_algorithm = AES_256_GCM
```

---

## 五、商用密码使用许可申请

### 5.1 申请要求

依据《商用密码管理条例》（国务院令第273号）：

| 要求 | 满足情况 |
| --- | --- |
| 使用国密产品（GM/T 0028 认证）| 🔲 需采购商用 SDK |
| 信息系统密码应用方案（20-50 页）| 🔲 v3.3 W-3 编制 |
| 商用密码检测中心测评 | 🔲 v3.3 W-3 测评 |
| 备案 | 🔲 v3.3 W-3 备案 |

### 5.2 商用国密 SDK 推荐

| 厂商 | 产品 | 资质 | 价格 |
| --- | --- | --- | --- |
| 三未信安 | SJJ1528-GM | GM/T 0028 二级 | 50-100 万 |
| 卫士通 | SJW051-GM | GM/T 0028 二级 | 80-150 万 |
| 华为 | KMS 国密版 | 国密局推荐 | 30-50 万/年 |
| 阿里云 | KMS 国密版 | 国密局推荐 | 20-40 万/年 |

**推荐**：阿里云 KMS 国密版（云服务，部署快，按需付费）

### 5.3 商密许可申请流程

```
1. 准备信息系统密码应用方案（v3.3 W-3 产出）
2. 提交省级密码管理局
3. 初审（5 工作日）
4. 技术审查（30 工作日）
5. 商用密码检测中心测评（5 工作日现场）
6. 决定（10 工作日）
7. 颁发证书 + 备案
```

**总周期**：6-8 周

---

## 六、Vault + 国密 SDK 集成代码

### 6.1 VaultTransitGmSm4Service.java

```java
@Service
public class VaultTransitGmSm4Service {

    @Autowired private VaultTemplate vaultTemplate;
    @Autowired private Sm4Sdk sm4Sdk;  // 商用国密 SDK（如阿里云 KMS）

    public byte[] encrypt(String keyName, byte[] plaintext) {
        // 1. 从 Vault 拉取密钥密文
        String encryptedKeyB64 = vaultTemplate.read("secret/gmsm/" + keyName)
            .getData().get("key");

        // 2. 用 KMS 解密密钥（仅 KMS 能解，应用层拿明文）
        byte[] key = sm4Sdk.decrypt(encryptedKeyB64);

        // 3. 用 SM4 加密数据
        return SM4Util.encryptCbc(plaintext, key, sm4Sdk.generateIv());
    }
}
```

### 6.2 VaultKvGmConfig.java

```java
@Configuration
public class VaultKvGmConfig {

    @Bean
    public VaultTemplate vaultGmTemplate(VaultEndpoint endpoint) {
        // 配置 Vault 客户端连接 KMS 后端
        // 国密密钥通过 KMS 解密，不在 Vault 中存明文
        return new VaultTemplate(endpoint, ...);
    }
}
```

---

## 七、监控与告警

### 7.1 密钥使用监控

| 指标 | 阈值 | 告警 |
| --- | --- | --- |
| 国密密钥使用率 | < 80% | 高负载告警 |
| 双轨解密失败 | > 1% | 密钥不匹配告警 |
| 国密切换 | 每次 | P2 通知（手动操作）|

### 7.2 性能监控

| 算法 | 预期吞吐（单线程） |
| --- | --- |
| AES-256-GCM | 500 MB/s |
| SM4-CBC | 200 MB/s（软实现）|
| SM4-GCM（商用 SDK）| 400 MB/s |
| SM3 摘要 | 300 MB/s |

**说明**：SM4 软件实现比 AES 慢约 60%（无 AES-NI 加速）。商用 SDK 通过国密硬件加速可达接近 AES 性能。

---

## 八、风险登记

| ID | 风险 | 缓解 |
| --- | --- | --- |
| K-1 | BouncyCastle 未 GM/T 0028 认证 | v3.4+ 切换商用 SDK |
| K-2 | SM4 性能不达标 | 商用 SDK 硬件加速 |
| K-3 | 双轨数据不一致 | 灰度 14 天 + 数据校验脚本 |
| K-4 | 商密许可申请被拒 | 提前咨询 + 完善方案 |
| K-5 | 商用 SDK 兼容性问题 | POC 验证后采购 |

---

## 九、附件

- `docs/key-management.md` — 密钥生命周期管理
- `modify/2026-08-12-国密合规认证方案.md` — 商密认证完整方案
- `backend/src/main/java/com/example/edam/crypto/` — 算法路由 + SM 工具类

---

**Vault Transit 国密双轨集成方案完成。** 等待团队按 4 周节奏实施。