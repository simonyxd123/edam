# EDAM SSO 集成指南（v3.3 W-4）

- 文档版本：v1.0
- 编制日期：2026-08-25
- 支持协议：SAML 2.0 + OIDC（双协议）
- 对应方案书：v3.3 §13.4 数据出境评估 + §9.4 Vault 高可用

---

## 一、SSO 架构

```
┌────────────────────────────────────────────────────────────────┐
│                       用户浏览器                                │
└────────┬───────────────────────────────────────────────────────┘
         │ ① 访问 EDAM 应用
         ▼
┌────────────────────────────────────────────────────────────────┐
│  EDAM 后端（Spring Security + SAML2 + OAuth2 Client）         │
│  - SsoAuthenticationFilter（路由 /auth/sso/*）               │
│  - SsoProviderRegistry（Provider 路由）                       │
│  - SsoUserProvisioning（JIT 自动开通）                       │
└────────┬───────────────────────────────────────────┬────────────┘
         │ ② SAML AuthnRequest / OIDC Authorize      │ ④ SAML Response / OIDC Code
         ▼                                            ▼
┌────────────────────────────────────────────────────────────────┐
│         企业 IdP（Keycloak / Okta / Azure AD）              │
└────────────────────────────────────────────────────────────────┘
```

---

## 二、支持的身份提供商

### 2.1 已验证兼容

| IdP | 协议 | 状态 | 推荐度 |
| --- | --- | --- | --- |
| **Keycloak** | SAML 2.0 + OIDC | ✅ 推荐 | ⭐⭐⭐⭐⭐ |
| **Okta** | SAML 2.0 + OIDC | ✅ 推荐 | ⭐⭐⭐⭐⭐ |
| **Azure AD**（Microsoft Entra ID）| SAML 2.0 + OIDC | ✅ 推荐 | ⭐⭐⭐⭐⭐ |
| **Google Workspace** | OIDC | ✅ | ⭐⭐⭐⭐ |
| **阿里云 IDaaS** | OIDC | ✅ 国内推荐 | ⭐⭐⭐⭐ |
| **Authing** | OIDC | ✅ 国内 | ⭐⭐⭐⭐ |

### 2.2 配置示例（application.yml）

```yaml
edam:
  sso:
    enabled: true
    enforce: false  # true = 禁用本地密码登录（仅 SSO）
    default-provider: keycloak
    jit-enabled: true
    default-dept-code: external
    default-role-code: employee

    providers:
      # Keycloak
      - id: keycloak
        display-name: 企业 Keycloak
        protocol: oidc
        enabled: true
        issuer: https://keycloak.example.com/realms/edam
        client-id: edam-backend
        client-secret: ${KEYCLOAK_CLIENT_SECRET}
        authorization-uri: https://keycloak.example.com/realms/edam/protocol/openid-connect/auth
        token-uri: https://keycloak.example.com/realms/edam/protocol/openid-connect/token
        userinfo-uri: https://keycloak.example.com/realms/edam/protocol/openid-connect/userinfo
        jwks-uri: https://keycloak.example.com/realms/edam/protocol/openid-connect/certs
        redirect-uri: https://api.example.com/auth/sso/callback/keycloak
        attribute-mapping:
          user-id: sub
          employee-no: preferred_username
          real-name: name
          email: email
          department: department
          roles: groups

      # Azure AD
      - id: azure-ad
        display-name: Microsoft Entra ID
        protocol: saml2
        enabled: true
        entity-id: https://api.example.com/auth/sso/metadata/azure-ad
        sso-url: https://login.microsoftonline.com/<tenant-id>/saml2
        idp-entity-id: https://sts.windows.net/<tenant-id>/
        idp-metadata-url: https://login.microsoftonline.com/<tenant-id>/federationmetadata/2007-06/federationmetadata.xml
        sp-private-key: ${AZURE_SP_PRIVATE_KEY}
        sp-certificate: ${AZURE_SP_CERTIFICATE}
        attribute-mapping:
          user-id: NameID
          employee-no: http://schemas.xmlsoap.org/ws/2005/05/identity/claims/employeeid
          real-name: http://schemas.microsoft.com/identity/claims/displayname
          email: http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress
```

---

## 三、接入步骤

### 3.1 在 IdP 配置 SP（Keycloak 示例）

1. **创建 Realm**：`edam`
2. **创建 Client**：
   - Client ID: `edam-backend`
   - Client Protocol: `openid-connect`
   - Root URL: `https://api.example.com`
   - Valid Redirect URIs: `https://api.example.com/auth/sso/callback/keycloak`
3. **获取 Client Secret**：复制到 `KEYCLOAK_CLIENT_SECRET` 环境变量
4. **配置 User Attributes**：确保 employee_no、department 字段已映射到 LDAP/AD

### 3.2 在 EDAM 配置 IdP

1. **编辑 `application.yml`**：填入 Issuer、Client ID、Secret
2. **重启后端**：`kubectl rollout restart deployment/backend`
3. **验证 Metadata**：`curl https://api.example.com/auth/sso/metadata/keycloak`

### 3.3 启用强制 SSO（可选）

```yaml
edam:
  sso:
    enforce: true  # 禁用本地密码登录
```

启用后：
- `/auth/login` 仅支持 SSO 回调（直接访问 401）
- 员工只能通过企业 IdP 登录

### 3.4 测试登录

1. 访问 `https://app.example.com/login`
2. 点击「企业 SSO 登录」
3. 跳转到企业 IdP（如 Keycloak）
4. 输入企业账号密码
5. IdP 回调 → EDAM 自动开通用户 → 跳转回应用

---

## 四、JIT Provisioning（自动开通）

### 4.1 行为

| 场景 | EDAM 行为 |
| --- | --- |
| 首次 SSO 登录 | 自动创建 EDAM 用户（默认部门 / 角色）|
| 已存在用户 SSO 登录 | 更新姓名、邮箱、部门 |
| IdP 账号禁用 | EDAM 同步禁用 |
| 员工离职 | IdP 关闭账号 → EDAM 自动 status=disabled |
| 跨 IdP 同工号 | 优先使用首次登录的 Provider（可配置）|

### 4.2 属性映射

| IdP 属性 | EDAM 字段 | 必填 |
| --- | --- | --- |
| sub / NameID | user_id | ✅ |
| employeeNumber / preferred_username | employee_no | ✅ |
| cn / displayname | real_name | ✅ |
| mail | email | ✅ |
| department | dept_id | ⚠️ |
| groups / roles | roles | ⚠️ |

### 4.3 自定义映射

```yaml
attribute-mapping:
  user-id: sub
  employee-no: edam_employee_no  # 自定义 IdP 属性名
  department: edam_department
  roles: edam_roles  # 多个用逗号分隔
```

---

## 五、安全加固

### 5.1 必须启用

| 项 | 配置 |
| --- | --- |
| **HTTPS** | 所有 IdP 通信强制 TLS 1.2+ |
| **PKCE** | OIDC 必须启用 PKCE（S256） |
| **state 参数** | 防止 CSRF（OAuth 2.0 标准） |
| **IdP 签名验证** | SAML 必须验证 IdP 签名（X.509 证书）|
| **nonce 验证** | OIDC 必须验证 nonce（防 ID Token 重放）|
| **时钟同步** | 验证 IdP 时间戳（±5 分钟）|
| **JWKS 缓存** | 缓存 IdP JWKS（避免每次请求拉取）|

### 5.2 推荐启用

| 项 | 配置 |
| --- | --- |
| **双向认证** | SAML 签名 AuthnRequest（防 SP 伪造） |
| **证书固定** | OIDC 的 JWKS endpoint 固定 |
| **多 IdP 冗余** | 同时配置 2 个 IdP（主备）|
| **风险评估** | IdP 风险等级 + 二次验证 |

### 5.3 不安全配置（禁止）

- ❌ 禁用签名验证
- ❌ 使用 HTTP（非 HTTPS）
- ❌ 禁用 state 参数
- ❌ 长期 access_token（应 ≤ 1 小时）
- ❌ refresh_token 长期保存（应 ≤ 7 天 + 单次使用）

---

## 六、登录流程详解

### 6.1 OIDC 授权码流程（+ PKCE）

```
1. 用户访问 https://app.example.com
2. 前端跳转 https://api.example.com/auth/sso/login/keycloak
3. 后端生成 state + code_verifier + code_challenge
4. 重定向到 IdP：
   https://keycloak/auth?
     response_type=code
     &client_id=edam-backend
     &redirect_uri=.../callback/keycloak
     &state=<随机>
     &code_challenge=<SHA256(verifier)>
     &code_challenge_method=S256
5. 用户在 IdP 输入账号密码（IdP 自己处理）
6. IdP 回调 EDAM：
   https://api.example.com/auth/sso/callback/keycloak?
     code=<一次性>
     &state=<原值>
7. EDAM 验证 state → 用 code + verifier 换 access_token
8. EDAM 用 access_token 拉 userinfo
9. EDAM JIT 创建/更新用户
10. EDAM 创建 EDAM Session + JWT Token
11. EDAM 重定向到前端：
    https://app.example.com/sso-callback?
      token=<JWT>
      &refresh_token=<refresh>
```

### 6.2 SAML 2.0 流程

```
1-2. 同 OIDC
3. 后端构造 SAML AuthnRequest XML
4. 重定向到 IdP：
   https://idp.example.com/sso?
     SAMLRequest=<base64(XML)>
     &RelayState=keycloak
5-6. 用户在 IdP 登录
7. IdP POST SAML Response（XML + Base64）到 EDAM：
   POST /auth/sso/callback/keycloak
     SAMLResponse=<base64>
8. EDAM 验证 SAML 签名（X.509）
9. EDAM 提取 AttributeStatement
10-11. EDAM JIT 创建用户 + 返回 Token
```

---

## 七、监控与告警

### 7.1 关键指标

| 指标 | 阈值 | 告警 |
| --- | --- | --- |
| SSO 登录失败率 | > 5% | P2 |
| IdP 连接超时 | > 10s | P1 |
| JIT Provisioning 失败 | > 1% | P2 |
| SAML 签名验证失败 | > 0 | P0（疑似伪造）|
| 跨 IdP 异常登录 | > 10/h | P1 |

### 7.2 审计日志

| 字段 | 来源 |
| --- | --- |
| timestamp | 自动 |
| user_id / employee_no | IdP userinfo |
| provider | SSO Provider ID |
| operation | login / provision / disable |
| ip | 请求 |
| result | success / failure |

---

## 八、故障排查

### 8.1 常见问题

| 现象 | 原因 | 解决 |
| --- | --- | --- |
| 重定向到 IdP 后白屏 | IdP 不可达 | 检查 `issuer` + 网络 |
| IdP 回调后 401 | state 不匹配 | 检查 CSRF Token |
| 用户属性为空 | IdP 未映射属性 | 检查 AttributeMapping |
| JIT 失败 | 数据库异常 | 查看 `sso_user_provision_failed` 日志 |
| 签名验证失败 | IdP 证书过期 | 更新 `idpMetadataUrl` 缓存 |
| access_token 过期快 | IdP 配置 TTL 短 | 检查 IdP token lifetime |

### 8.2 调试模式

```yaml
logging:
  level:
    com.example.edam.security.sso: DEBUG
```

日志示例：
```
DEBUG - sso_callback_received provider_id=keycloak code=xxx state=yyy
DEBUG - oidc_userinfo_resolved user_id=xxx email=xxx@xxx.com
DEBUG - sso_user_provisioned user_id=123 employee_no=SA0001
INFO  - sso_login_success user_id=123 provider=keycloak
```

---

## 九、迁移路径

### 9.1 从本地密码迁移到 SSO

```
阶段 1（1 个月）：SSO 可选（保留本地密码）
阶段 2（2 个月）：默认 SSO + 本地密码备用
阶段 3（3 个月）：强制 SSO + 关闭本地密码（edam.sso.enforce=true）
阶段 4（长期）：彻底移除本地密码登录
```

### 9.2 应急回滚

如 SSO 故障导致全员无法登录：
```yaml
edam:
  sso:
    enforce: false  # 临时关闭强制
    enabled: false  # 紧急关闭 SSO（仅本地密码）
```

---

## 十、合规对齐

| 标准 | 条款 | 满足情况 |
| --- | --- | --- |
| 等保三级 | 8.1.3.2 访问控制规则 | ✅ |
| GB/T 35273 | 第三方授权 + 告知 | ✅ |
| GDPR | 数据最小化 | ✅（仅必要属性）|

---

## 十一、相关文档

- `docs/key-management.md` — 密钥管理
- `docs/personal-information-assessment.md` — PIA 评估
- `modify/2026-08-12-威胁建模报告.md` — STRIDE 35 条（含 SSO 相关威胁）|

---

**SSO 集成指南完成。** 等待团队按 IdP 配置 + 集成步骤实施。