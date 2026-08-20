# WebAuthn / FIDO2 无密码登录集成指南（v3.3 W-5）

- 文档版本：v1.0
- 编制日期：2026-08-26
- 标准：W3C WebAuthn Level 2 + FIDO2
- 对应方案书：v3.3 §9.4 Vault 高可用 + §13.1 个人信息保护

---

## 一、WebAuthn 简介

WebAuthn 是 W3C 标准，浏览器 + 平台认证器（指纹 / 面容 / 硬件密钥）实现无密码登录。

**优势**：
- ✅ 无密码（防钓鱼、防撞库、防泄露）
- ✅ 公钥加密（私钥永不出设备）
- ✅ 抗中间人（origin 绑定）
- ✅ 抗重放（counter 递增）

**限制**：
- 需要浏览器支持（Chrome / Safari / Edge / Firefox）
- 需要设备支持（指纹 / 面容 / 硬件密钥）
- 用户首次注册流程较复杂

---

## 二、注册流程

### 2.1 流程图

```
┌──────────┐                ┌──────────┐                ┌──────────────┐
│ 浏览器    │                │ EDAM 后端│                │ 平台认证器    │
│ (前端)   │                │          │                │ (指纹/面容) │
└────┬─────┘                └────┬─────┘                └──────┬───────┘
     │                           │                           │
     │ ① POST /register/begin   │                           │
     │   { name: "工作指纹" }    │                           │
     ├──────────────────────────>│                           │
     │                           │ 生成 challenge            │
     │                           │ 存 Redis (TTL 5min)        │
     │<──────────────────────────┤                           │
     │ { challenge, rpId, ... }  │                           │
     │                           │                           │
     │ ② navigator.credentials   │                           │
     │   .create({...})          │                           │
     ├──────────────────────────────────────────────────────>│
     │                                                       │ ③ 用户指纹验证
     │<─────────────────────────────────────────────────────┤
     │ { attestation, clientDataJSON }                        │
     │                           │                           │
     │ ③ POST /register/complete │                           │
     │   { credential, ... }     │                           │
     ├──────────────────────────>│                           │
     │                           │ 验证 attestation          │
     │                           │ 验证 challenge            │
     │                           │ 存储 publicKey + counter │
     │<──────────────────────────┤                           │
     │ { success: true }          │                           │
```

### 2.2 端点

#### POST /auth/webauthn/register/begin

请求：
```json
{ "name": "工作笔记本指纹" }
```

响应（PublicKeyCredentialCreationOptions）：
```json
{
  "challenge": "随机base64url",
  "rp": {
    "id": "example.com",
    "name": "EDAM"
  },
  "user": {
    "id": "base64url(employee_no)",
    "name": "SA0001",
    "displayName": "张三"
  },
  "pubKeyCredParams": [
    {"alg": -7, "type": "public-key"},
    {"alg": -257, "type": "public-key"}
  ],
  "timeout": 60000,
  "attestation": "none",
  "authenticatorSelection": {
    "residentKey": true,
    "userVerification": "preferred",
    "authenticatorAttachment": "platform"
  },
  "excludeCredentials": [
    {"id": "已注册凭据ID"}
  ]
}
```

#### POST /auth/webauthn/register/complete

请求：
```json
{
  "challenge": "原 challenge 值",
  "credentialId": "base64url",
  "attestation": "客户端返回的 attestation 对象",
  "clientDataJSON": "base64url",
  "name": "工作笔记本指纹"
}
```

响应：
```json
{
  "success": true,
  "message": "注册成功",
  "credentialId": "base64url"
}
```

---

## 三、登录流程

### 3.1 流程图

```
┌──────────┐                ┌──────────┐                ┌──────────────┐
│ 浏览器    │                │ EDAM 后端│                │ 平台认证器    │
└────┬─────┘                └────┬─────┘                └──────┬───────┘
     │                           │                           │
     │ ① POST /login/begin      │                           │
     │   { employeeNo: "SA0001"}│                           │
     ├──────────────────────────>│                           │
     │                           │ 生成 challenge            │
     │                           │ 加载用户已注册凭据列表      │
     │<──────────────────────────┤                           │
     │ { challenge, allowCredentials }                       │
     │                           │                           │
     │ ② navigator.credentials   │                           │
     │   .get({...})             │                           │
     ├──────────────────────────────────────────────────────>│
     │                                                       │ ③ 用户指纹验证
     │<─────────────────────────────────────────────────────┤
     │ { assertion, clientDataJSON, signature }              │
     │                           │                           │
     │ ③ POST /login/complete    │                           │
     │   { ... }                 │                           │
     ├──────────────────────────>│                           │
     │                           │ 验证 assertion 签名        │
     │                           │ 验证 challenge            │
     │                           │ 验证 counter 单调递增      │
     │                           │ 签发 JWT Token            │
     │<──────────────────────────┤                           │
     │ { success, access_token, refresh_token }               │
```

### 3.2 端点

#### POST /auth/webauthn/login/begin

请求：
```json
{ "employeeNo": "SA0001" }
```

响应：
```json
{
  "challenge": "随机base64url",
  "rpId": "example.com",
  "timeout": 60000,
  "allowCredentials": [
    "credentialId1",
    "credentialId2"
  ],
  "userVerification": "preferred"
}
```

#### POST /auth/webauthn/login/complete

请求：
```json
{
  "employeeNo": "SA0001",
  "challenge": "原 challenge 值",
  "credentialId": "base64url",
  "authenticatorData": "base64url",
  "clientDataJSON": "base64url",
  "signature": "base64url"
}
```

响应：
```json
{
  "success": true,
  "message": "登录成功",
  "accessToken": "eyJ...",
  "refreshToken": "eyJ..."
}
```

---

## 四、凭据管理

### 4.1 列出凭据

```http
GET /auth/webauthn/credentials
Authorization: Bearer eyJ...

200 OK
[
  {
    "id": 1,
    "credentialId": "base64url...",
    "name": "工作笔记本指纹",
    "credentialType": "platform",
    "userVerification": "preferred",
    "lastUsedAt": "2026-08-26T10:00:00",
    "createdAt": "2026-08-20T14:00:00"
  }
]
```

### 4.2 撤销凭据

```http
DELETE /auth/webauthn/credentials/{credentialId}
Authorization: Bearer eyJ...

204 No Content
```

### 4.3 备份码（Recovery Codes）

WebAuthn 没有原生备份码。生产建议：
- **A 方案**：强制注册 ≥ 2 个凭据（指纹 + 硬件密钥）
- **B 方案**：每个用户生成一次性备份码（10 个），存 Vault 加密
- **C 方案**：保留密码登录作为后备（混合模式）

---

## 五、前端集成（Vue 3 + TypeScript 示例）

### 5.1 注册

```typescript
// src/views/WebAuthnRegister.vue
async function registerWebAuthn(name: string) {
  // 1. 获取 challenge
  const { data: opts } = await api.post('/auth/webauthn/register/begin', { name })

  // 2. 转 base64url → ArrayBuffer
  const publicKey = {
    ...opts,
    challenge: base64urlToBuffer(opts.challenge),
    user: {
      ...opts.user,
      id: base64urlToBuffer(opts.user.id),
    },
    excludeCredentials: opts.excludeCredentials.map(c => ({
      id: base64urlToBuffer(c.id),
    })),
  }

  // 3. 调用浏览器 API
  const credential = await navigator.credentials.create({ publicKey })

  // 4. 提交注册
  const result = await api.post('/auth/webauthn/register/complete', {
    challenge: opts.challenge,
    credentialId: bufferToBase64url(credential.rawId),
    attestation: bufferToBase64url(credential.response.attestationObject),
    clientDataJSON: bufferToBase64url(credential.response.clientDataJSON),
    name,
  })

  return result
}
```

### 5.2 登录

```typescript
async function loginWithWebAuthn(employeeNo: string) {
  // 1. 获取 challenge
  const { data: opts } = await api.post('/auth/webauthn/login/begin', { employeeNo })

  const publicKey = {
    ...opts,
    challenge: base64urlToBuffer(opts.challenge),
    allowCredentials: opts.allowCredentials.map(id => ({
      id: base64urlToBuffer(id),
      type: 'public-key',
    })),
  }

  // 2. 调浏览器 API（自动指纹验证）
  const assertion = await navigator.credentials.get({ publicKey })

  // 3. 提交登录
  const { data: result } = await api.post('/auth/webauthn/login/complete', {
    employeeNo,
    challenge: opts.challenge,
    credentialId: bufferToBase64url(assertion.rawId),
    authenticatorData: bufferToBase64url(assertion.response.authenticatorData),
    clientDataJSON: bufferToBase64url(assertion.response.clientDataJSON),
    signature: bufferToBase64url(assertion.response.signature),
  })

  // 4. 保存 token
  localStorage.setItem('token', result.accessToken)
  return result
}
```

---

## 六、安全加固

### 6.1 必须启用

| 项 | 实现 |
| --- | --- |
| **HTTPS** | 所有 WebAuthn 通信强制 TLS 1.2+ |
| **Origin 验证** | clientDataJSON.origin 必须匹配 `rpOrigin` |
| **Challenge 一次性** | Redis TTL 5 分钟 + 消费后立即删除 |
| **Counter 单调递增** | 防止攻击者重放旧 signature |
| **签名验证** | ES256 / RS256 / EdDSA 等公认算法 |
| **rpIdHash 验证** | SHA-256(rpId) 必须匹配 |

### 6.2 推荐启用

| 项 | 实现 |
| --- | --- |
| **用户验证（UV）** | 指纹 / 面容 / PIN（userVerification=required）|
| **Resident Key** | 凭据存储在认证器（discoverable credential）|
| **Attestation** | attestation=direct 可验证认证器真实性 |
| **Backup 凭据** | 至少注册 2 个凭据（防丢失）|
| **多凭据** | 支持平台 + 跨平台（漫游密钥）|

### 6.3 备份策略

| 方案 | 描述 | 推荐度 |
| --- | --- | --- |
| 多个平台认证器 | 笔记本指纹 + 手机指纹 | ⭐⭐⭐⭐⭐ |
| 跨平台硬件密钥 | YubiKey 5 / Feitian K9 | ⭐⭐⭐⭐⭐ |
| 一次性备份码 | 10 个 8 位码（存 Vault） | ⭐⭐⭐⭐ |
| 保留密码登录 | 传统密码作为后备 | ⭐⭐⭐ |

---

## 七、监控与告警

| 指标 | 阈值 | 告警 |
| --- | --- | --- |
| 注册成功率 | < 90% | 浏览器兼容性问题 |
| 登录失败率 | > 5% | 用户操作或凭据问题 |
| Challenge 过期率 | > 10% | 网络延迟问题 |
| Counter 不递增 | 任何 | P0 告警（重放攻击）|
| 异常 IP 注册 | > 5/h | 撞库攻击 |

---

## 八、浏览器兼容性

| 浏览器 | 最低版本 | 支持情况 |
| --- | --- | --- |
| Chrome | 67+ | ✅ |
| Safari | 14+ | ✅ |
| Edge | 18+ | ✅ |
| Firefox | 60+ | ✅ |
| Opera | 54+ | ✅ |

### 8.1 认证器支持

| 类型 | 设备 |
| --- | --- |
| **平台** | Windows Hello（指纹 / 面容 / PIN）、Touch ID、Face ID |
| **跨平台** | YubiKey、Feitian K9、Google Titan |

### 8.2 移动端

| 平台 | 支持 |
| --- | --- |
| iOS 14+ | ✅（Safari + Touch ID / Face ID）|
| Android 9+ | ✅（Chrome + 指纹）|

---

## 九、与现有系统集成

### 9.1 与密码登录并存

```yaml
edam:
  password:
    enabled: true
  webauthn:
    enabled: true
  sso:
    enabled: true
```

用户可选：
- 密码登录（传统）
- WebAuthn 登录（无密码）
- SSO 登录（企业 IdP）

### 9.2 强制 WebAuthn（高级安全场景）

```yaml
edam:
  webauthn:
    required: true  # 禁用密码 + SSO，仅 WebAuthn
```

---

## 十、合规对齐

| 标准 | 条款 | 满足情况 |
| --- | --- | --- |
| 等保三级 | 8.1.5.4 密码应用 | ✅（无密码降低密码泄露风险）|
| GB/T 22239 | 身份鉴别 | ✅（多因素）|
| NIST 800-63B | AAL3 | ✅（硬件认证器 + 服务端验证）|

---

## 十一、相关文档

- `docs/sso-integration-guide.md` — SSO 集成
- `docs/key-management.md` — 密钥管理
- `modify/2026-08-12-威胁建模报告.md` — STRIDE 35 条

---

**WebAuthn 集成指南完成。** 等待团队按 3 周节奏实施 + 浏览器兼容性测试。