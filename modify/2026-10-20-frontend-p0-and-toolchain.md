# 前端 P0 修复 + 工具链补全（第二轮）

- 日期：2026-10-21
- 范围：web/
- 承接：`modify/2026-10-20-full-project-review-and-p0-fixes.md`

---

## 一、前端工具链：CI 的三个步骤此前一个都跑不通

### 现状

`web/` 目录**没有** `tsconfig.json`、没有 eslint 配置、没有 vitest 配置、
**没有 package-lock.json**、没有任何测试文件。而 CI 的 `frontend:build`：

```yaml
- npm ci       # ← 没有 package-lock.json，npm ci 直接报错退出
- npm run lint # ← ESLint 找不到配置文件
- npm test     # ← vitest 无配置无测试
- npm run build# ← vue-tsc --noEmit 找不到 tsconfig
```

即**前端从未被类型检查过**，那个 job 从建仓起就是红的。

### 修复

| 文件 | 说明 |
|---|---|
| `web/tsconfig.json` | strict + `paths` 别名 + `noUnusedLocals` + `noUnusedParameters` |
| `web/tsconfig.node.json` | vite.config.ts 的独立工程 |
| `web/.eslintrc.cjs` | eslint 8 传统格式，vue-eslint-parser + @typescript-eslint |
| `web/package-lock.json` | 补齐（lockfileVersion 3，377 个包）—— `npm ci` 的前提 |
| `web/vite.config.ts` | 加 `test` 段（jsdom / globals / coverage） |
| `web/src/utils/date.spec.ts` | 9 个用例 |
| `web/src/utils/session.spec.ts` | 6 个用例 |

**结果**：`npm run lint` 0 error / 11 warning、`npm test` 15 passed、
`npm run build`（含 `vue-tsc --noEmit`）通过。类型错误从 43 → **0**。

---

## 二、顺手抓到的生产 bug：全应用时间戳毫秒位显示成字面量 "SS"

写单测时发现 `formatDateTime('2026-08-25 10:14:32.29')` 返回
`2026-08-25 10:14:32.SS` —— 毫秒位是**字面量 "SS"**，不是数字。

原因：`FMT_DATETIME = 'YYYY-MM-DD HH:mm:ss.SS'`。这个 `SS` 是从
Java 的 `DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SS")` 抄过来的，
但 **dayjs 不认 `SS` / `S` 这两个 token**（只有 `SSS` 有效），
会被原样输出。

也就是说**整个系统所有页面的时间戳都显示成 `...:32.SS`**。

修复：
- `FMT_DATETIME` 改 `SSS`（3 位毫秒，与后端 Jackson 的 ISO 输出对齐）
- 解析时对 Java 的 2 位 `.SS` 输入先补零再解析

---

## 三、崩溃 / 白屏

| 位置 | 现象 | 根因 | 修复 |
|---|---|---|---|
| `Distribution.vue` | 列表页**整页白屏** | 后端 list 返回原始实体（camelCase + `status` 是 Integer），前端取 `row.external_recipient.name` → `undefined.name` → 渲染中断 | 后端已改（逐条 `toResponse()`）；前端加 `row as Approval` 断言 |
| `Settings.vue` | 点一下「设置」菜单**当场被登出** | 用裸 `axios`（无 Authorization 头）调 `/auth/me` → 401 → 命中全局 XHR 拦截器 → 清 token + 跳登录 | 改用项目的 `api` 客户端（自动注入 JWT） |
| `router/index.ts` | 无限重定向 → 栈溢出 / 白屏 | `fetchMe()` 内部 catch 后只调 `logout()` 就**正常返回**，Promise 永远 resolve → 守卫的 `.catch(() => next({name:'Login'}))` 是死代码 → 走 then 分支 → `user` 仍 null → `hasPermission` 恒 false → 跳 Dashboard → Dashboard 也有 permission → 递归 | `fetchMe` 失败时**必须 reject**；守卫 catch 分支改跳 Login |
| `App.vue` | 启动时恢复登录态形同虚设 | `if (!userStore.isLoggedIn) userStore.tryRestore()`，而 `isLoggedIn` 就是 `!!token` → 没 token 进不去，有 token 也不进 → `tryRestore` 永远不执行 | 改成无条件调用 `tryRestore()` |

---

## 四、401 处理散落 4 处 → 收敛为 1 个模块

新增 `web/src/utils/session.ts`：
- `handleSessionInvalid(reason)` —— 清 token + 提示 + 跳登录页，带防重入标志
- `isApiCall(url, baseUrl)` —— 判断 API 路径
- `isPublicEndpoint(url)`

### 关键 bug：`isApiCall` 恒为 false

原 `client.ts`：

```ts
const url = error.config?.url || '';
const isApiCall = url.startsWith('/api/') || url.includes('/api/');
```

axios 的 `config.url` 是**相对路径**（`/videos`），完整 URL 由 baseURL
在 adapter 层拼接 —— `buildFullPath` 发生在拦截器**之后**。
所以 `isApiCall` **恒为 false** → `isSessionInvalid` 恒为 false →
整个会话失效分支是死代码。

更糟的是那个分支的条件写成 `status >= 400 && status < 500`：
一旦修好 `isApiCall`，**403 权限不足、404 资源不存在、409 冲突全都会被
当成"会话失效"** → 清 token + 跳登录页。删一个不存在的视频返回 404，
用户被登出。

修复：判断时拼上 `config.baseURL`；条件收敛为**只认 401**（+ 真正的
网络无响应），403 单独提示"没有权限"。

---

## 五、其它

| 位置 | 问题 | 修复 |
|---|---|---|
| `api/client.ts` | `api.post(url, data, config)` 的第三参**不存在** —— 调用方为传 multipart Content-Type 只能写 `as any`，而那个 config **被整个丢弃**，请求带着默认 `application/json` 发出 | `get`/`post` 补 `config?: AxiosRequestConfig` 形参 |
| `api/auth.ts` | `logout` 只传 `user_id`，后端要求 `refresh_token` 才吊销会话 → **服务端会话从不吊销**，JWT 在有效期内依然可用 | 传 `refresh_token` + `user_id` |
| `api/auth.ts` | `CurrentUser` 声明 `last_login_time`，后端实际是 `last_login_at`；漏了 `email`/`phone`/`status` | 对齐后端 |
| `stores/user.ts` | `logout()` 等服务端返回才清本地状态 → 登出有延迟 | 先同步清本地，再异步通知服务端 |
| `VideoDetail.vue` | `videoId` 是一次性快照 → 从 `/videos/1` 跳 `/videos/2` 时 vue-router 复用组件实例，**播的还是上一个视频** | 改 `computed` + `watch` 重载（并先 `destroyHls()`） |
| `VideoDetail.vue` | 重试成功后 `errorMsg` 仍非空 → 模板 `v-else-if="errorMsg"` 继续渲染红字 + `v-show="... && !errorMsg"` 把播放器**整个隐藏** → 视频能播了但用户只看到永久错误横幅 | 在 `MANIFEST_PARSED`（恢复成功）时清横幅 |
| `Videos.vue` | `v-model:current-page="searchForm"` 绑的是**筛选条件对象** → pager 永远显示 1，翻页后改筛选条件会拉第 1 页但页码停在 3 | 引入独立的 `page` ref |
| `Documents.vue` | `el-tag :type` 传 `''` 不是合法 tag type | 抽 `classificationTagType()` 返回 `TagType \| undefined` |
| 全局 | 11 处 `catch (e) {}` 空吞错；6 处 `if (e !== 'cancel')` —— Element Plus 关闭弹窗 reject 的是 **`'close'`** 不是 `'cancel'` → 点右上角 × 会弹"删除失败：undefined" | 待下一轮统一收敛（抽 `isDialogDismiss`） |

---

## 验证

```
npm run lint   → 0 error, 11 warning   (exit 0)
npm test        → 15 passed / 2 files
npm run build   → vue-tsc --noEmit 通过 + vite build 成功
vue-tsc --noEmit → 0 type error
```

按本项目 git-auto-commit 约定自动 commit + push。
