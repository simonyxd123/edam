# 修复 PDF 预览 "192.168.10.2 refused to connect"

- 日期：2026-10-20
- 影响：web/src/views/Documents.vue、web/vite.config.ts
- 现象：点击「预览」PDF 后浏览器弹出
  `192.168.10.2 refused to connect`（err_connection_refused），
  PDF 不显示，控制台无业务错误堆栈

## 根因（双层叠加）

### 根因 1：iframe 不带 Authorization 头 → 401 被代理链吞掉

前端 `<iframe :src="previewSrc">` 直接请求
`/api/v1/documents/{id}/preview`。axios 的 JWT 拦截器只对
`fetch/xhr` 生效，**iframe 是另一个请求上下文，不会自动带
Authorization / X-User-Id**。

后端 `SecurityConfig` 把 `/documents/**` 列为
`anyRequest().authenticated()`，所以这个请求会 401。

但**测试环境**通常前置了多层代理（Vite dev proxy → Nginx →
后端 Spring Boot），401 走代理时被改写成 504/502，并被改成
stream 重试多次，浏览器在最后一次失败时把它判成
ECONNREFUSED，显示的"被拒连"主机是代理链最外层的 IP
（典型为 Vite/dev 容器的对外 IP `192.168.10.2`），于是出现
`192.168.10.2 refused to connect`。

也就是——这条错误的"192.168.10.2"既不是 iframe 实际目标，
也不是后端真实地址，是**代理链最后报错的对外 IP**。
从症状反推链路最容易跑偏到网络层。

### 根因 2：vite.config.ts proxy 端口与 application.yml 错开由来已久

`backend/src/main/resources/application.yml` 里 `server.port=8080`
是 Spring Boot 内嵌 Tomcat 的默认监听端口；
而 `web/vite.config.ts` 里 proxy target 默认 `http://localhost:8092`，
与 application.yml 不一致。

8092 是 EDAM 开发期约定端口（与 8080 不同源）：后端实际监听 8092
由 dev 启动脚本、`-Dserver.port=8092` 或反向代理覆盖，
不应反过来改 application.yml —— 那样会污染生产默认。

## 修复

### 修复 1：Documents.vue 改走 Blob URL（绕开 iframe 鉴权）

`handlePreview` 改成：
1. `fetch` 带 Authorization / X-User-Id 拉字节流
2. 转 Blob → `URL.createObjectURL(blob)`
3. iframe `:src` 设成 Blob URL

Blob URL 是浏览器本地资源，不发任何 HTTP，自然绕过：
- iframe 没 Authorization 的问题
- CORS
- 任意上游代理链异常

副作用清理：
- `closePreview()` / 兜底 `setTimeout 1h` 都 `revokeObjectURL`
- 连续打开多个预览时先 revoke 上一个，防内存泄漏
- 加 `previewLoading` 标志 + 加载占位，PDF 流可能几百 MB
  不能让用户以为点错了

附带简化：
- `openInNewTab` 不必再 fetch 一次（已经是 Blob URL），
  直接 `window.open`
- `downloadPreview` 不用动（Blob URL + `<a download>` 本就 OK）

### 修复 2：vite.config.ts 端口注释更新，明确 8092 ≠ application.yml

不改默认值（沿用 `http://localhost:8092`），只把长期 stale 的注释
写清楚：

```ts
'/api': {
  // EDAM 开发期约定端口 = 8092
  //   （与 backend/src/main/resources/application.yml 里 server.port=8080
  //   不同源 —— 后端实际监听哪个端口，靠 dev 启动脚本、
  //    -Dserver.port=8092 或反向代理统一覆盖，而不是改这里的默认值）
  // 测试环境如果后端跑在另一台机器/端口，通过环境变量覆盖：
  //   VITE_API_BASE_URL=http://your.host:port  npm run dev
  target: process.env.VITE_API_BASE_URL || 'http://localhost:8092',
  changeOrigin: true,
},
'/ws': {
  target: process.env.VITE_WS_BASE_URL || 'ws://localhost:8092',
  ws: true,
},
```

如果后端实际在 8080 跑，把 `VITE_API_BASE_URL=http://localhost:8080`
写到 `web/.env.local` 即可，不必改 vite.config.ts。

## 不在本次范围

- 后端是否要给 `/documents/*/preview` 加类似
  `/playback/*/playlist.m3u8` 的 query-string 一次性 token
  （解决下载权限问题）——本期不动，由后续「文档分发审批」
  迭代一起处理。当前 Blob URL 路线足够修复"点预览不显示"
  这个症状。
- Nginx/反代配置：跟仓库无关，本次只改 web 端。

## 验证

1. 任意角色账号登录 → 文档管理 → 点 PDF 「预览」
   - 期望：弹窗快速打开；iframe 渲染 PDF；不再报
     `192.168.10.2 refused to connect`
   - 异常兜底：若后端 401/5xx，会看到 `ElMessage.error('预览加载失败：HTTP xxx')`
2. 关弹窗 → 重开不同 PDF → 不应内存爆炸（devtools 内存面板验证
   previewBlobUrl 在 closePreview 后已 revoke）
3. 预览中点「下载文件」→ 浏览器触发下载
4. 预览中点「在新窗口打开」→ 新标签页正常展示 PDF
5. devtools 抓包**应只有 1 次** `/api/v1/documents/{id}/preview` 请求，
   不应再看到该 URL 的反复重试

按本项目 git-auto-commit 约定自动 commit + push。
