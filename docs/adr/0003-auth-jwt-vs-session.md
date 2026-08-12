# ADR-0003：鉴权方案（JWT vs Session-Cookie）

- **状态**：✅ 已接受
- **日期**：2026-08-12

## 上下文

业务后端需要为 Web 前端、移动端、第三方集成（Webhook、Prism Mock）提供鉴权。

## 评估

| 维度 | JWT（不透明签名） | Session-Cookie | 纯 JWT（透明） |
| --- | --- | --- | --- |
| 服务端可吊销 | ✅ 黑名单或短 TTL | ✅ | ❌ |
| 跨域 | 简单 | 需 CORS | 简单 |
| CSRF | 不需要 | 需要 CSRF Token | 不需要 |
| 移动端友好 | ✅ | 需适配 | ✅ |
| 性能 | 每次请求解密签名 | 查 Redis | 解密即可 |
| 状态存储 | 无 | Redis | 无 |

## 决策

**采用签名 JWT + Refresh Token 双 Token 方案**。

```
access_token: HMAC-SHA256 签名 JWT，TTL 5-10 分钟
refresh_token: 不透明字符串（64 字节随机），TTL 7 天，存 Redis
```

理由：
1. **access_token 短 TTL**：即使泄露影响有限
2. **refresh_token 存 Redis**：可主动吊销（登出、改密、员工离职）
3. **Token 不携带 PII**：解决 v1.0 评审中识别的 PII 泄露问题
4. **多端复用**：Web/移动端/API 调用方使用同一鉴权机制

## 关键实现

- 服务端：Spring Security + jjwt 库
- 签名密钥：存 Vault，双密钥灰度轮转
- 吊销：refresh_token 撤销 → 强制重新登录
- Token 内容：`user_id_hash`（SHA-256）而非明文 user_id

## 后果

- **正向**：安全可控（可吊销）+ 跨域简单 + 多端复用
- **负向**：相比纯 Session 多一次 Token 解析开销（< 1ms）
- **回顾**：v3.0 第六章 6.3 复审，与 PII 合规要求一致