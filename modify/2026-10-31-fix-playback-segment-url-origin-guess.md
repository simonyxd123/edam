# 换个账号就播不了：分片地址的 origin 猜错了

- 日期：2026-10-31
- 触发：服务器上 E000003 上传的视频，E000003 能播，换成 E000101 登录就播不了，
  报 "网络异常，正在重试（1/3）…" 然后 "视频源不可达 (HTTP ??)"

---

## 一、症状里最有信息量的一点："HTTP ??"

前端那句 `HTTP ??` 来自 `VideoDetail.vue`：

```js
const status = data.response?.code;
...
errorMsg.value = `视频源不可达 (HTTP ${status ?? '??'})` + ...
```

`status` 是 `undefined` 才会渲染成 `??`。

而 hls.js 只有在**超时**路径上才不挂 `response`（`hls.js` 的
`handleNetworkError`：`if (response) { errorData.response = ... }`，
超时走的是 `onTimeout` → `handleNetworkError(..., true, undefined, ...)`）。

⇒ **`HTTP ??` 的真实含义是：请求发出去了，但一个 HTTP 响应都没收到。**

这就把嫌疑范围收得很窄了 —— 不是 404（产物不存在）、不是 401（token 失效）、
不是 503（MinIO 挂了），这些都会带明确的状态码。

**一个"连不上"的分片请求**，而不是"后端返回了错误"。

---

## 二、根因：后端在猜浏览器的 origin，而且猜错了

原来的 `rewriteM3u8` 把 m3u8 里的分片/密钥地址改写成**绝对路径**，
base 由 `inferBrowserBase(request)` 推断，优先级：

1. `X-Forwarded-Proto` + `X-Forwarded-Host`
2. `Origin`
3. `Referer`
4. `request.getRequestURL()`

问题出在第 1 条 —— nginx 的标准配置是：

```nginx
proxy_set_header X-Forwarded-Host $host;
```

**`$host` 不含端口**（含端口的是 `$http_host`）。

部署环境的实际访问地址是 `https://218.4.173.194:65173`，
于是后端拿到 `X-Forwarded-Host: 218.4.173.194` ⇒ 猜出
`browserBase = https://218.4.173.194`（**端口 65173 丢了**）。

改写后的分片地址就变成了：

```
https://218.4.173.194/api/v1/playback/7/segment/segment_001.ts?token=...
                            ↑ 端口变成 443，不是 65173
```

### 为什么这个 bug 表现得"因人而异"

因为 `inferBrowserBase` 的 1/2/3 条优先级会因**访问方式不同**而给出不同结果：

| 访问方式 | 命中的分支 | 猜出的 base | 对不对 |
|---|---|---|---|
| 直连后端 8092（开发/本机） | 4（兜底） | `http://host:8092` | 对 |
| 经 nginx，但 nginx 没转发 X-Forwarded-* | 2/3（Origin/Referer） | `https://host:65173` | 对 |
| 经 nginx，且转发了 `X-Forwarded-Host: $host` | 1 | `https://host` | **错（丢端口）** |

E000003 与 E000101 的浏览器到服务器的路径/端口不一样（或者是 nginx
转发头的行为在这条链路上不一致），于是猜出来的 base 也就不一样 ——
一个猜对了能播，另一个猜错了就"连不上"。

**关键点：manifest 本身是同源相对路径，永远能下载成功**（前端拼的是
`window.location.origin + m3u8_url`）；**只有 m3u8 里面嵌的分片地址**
才是后端猜出来的那个。所以现象是"列表出来了、进度条不动、分片拉不到"，
而 m3u8 改写错这件事在服务端日志里完全看不出来。

---

## 三、修法：改成根相对路径，别猜了

### 3.1 后端

`PlaybackController.rewriteM3u8` 改写出的地址从绝对路径变成**根相对路径**：

```
/api/v1/playback/7/segment/segment_001.ts?token=...
/api/v1/playback/7/key?token=...
```

由 **hls.js 自己**用 manifest 的 URL 解析。manifest 是浏览器按同源
拉下来的，它的 origin/端口**必然正确**，所以分片地址也必然正确 ——
后端不再需要猜端口，`inferBrowserBase` 整个方法删掉。

用 hls.js 自己的 `URLToolkit.buildAbsoluteURL` 实测过：

```
base = https://218.4.173.194:65173/api/v1/playback/7/playlist.m3u8?token=JWT1
'/api/v1/playback/7/segment/segment_001.ts?token=JWT2'
  => https://218.4.173.194:65173/api/v1/playback/7/segment/segment_001.ts?token=JWT2  ✔ 端口保住了
```

### 3.2 前端：别再显示 "HTTP ???"

`HTTP ??` 对排查**零信息量** —— 它同时对应"地址猜错了""后端慢""防火墙丢包"
三种完全不同的原因。而 hls.js 自己在 `data.details` 里给了**精确到阶段**的结论
（`manifestLoadError` / `fragLoadError` / `keyLoadError` / `manifestLoadTimeOut` …），
原来只打进了 console。

新增 `web/src/utils/hlsError.ts`，把 `details` 映射成"哪个阶段 + 什么状态 + 往哪查"：

```
视频源不可达（视频分片 无响应（超时或连接被拒））：分片拉取超时，请查分片地址的 origin/端口是否与播放列表一致
```

顺带解决一个**凭据泄露**问题：播放地址里带的是**可用的播放 JWT**，
原来的 console 日志会把带 token 的完整 URL 打出来。现在统一打码成 `token=***`。

### 3.3 后端日志：能区分是谁的请求

`m3u8_endpoint_hit` / `m3u8_served` / `segment_serve_failed` / `hls_key_served`
等日志原来**不带用户 id**，E000003 和 E000101 的请求在日志里长得一模一样。
现在从 JWT 的 `sub` 里取请求者 id 带上（**只取 subject，不打 token 本身**）。

⇒ 下次再坏，日志里直接能看出"是谁在什么时候拉了哪个视频的分片"。

---

## 四、改动清单

| 文件 | 改动 |
|---|---|
| `backend/.../PlaybackController.java` | 分片/密钥地址改**根相对路径**；删掉 `inferBrowserBase`；日志加 `requester_id` |
| `backend/.../PlaybackControllerTest.java` | 新增 3 个用例：根相对改写、不受 `X-Forwarded-Host` 丢端口影响、非分片行不动 |
| `web/src/utils/hlsError.ts`（新） | hls.js 错误 → 中文阶段/状态/排查方向；token 打码 |
| `web/src/utils/hlsError.spec.ts`（新） | 11 个用例 |
| `web/src/views/VideoDetail.vue` | 用新 helper 生成错误文案；日志打码 |
| `doc/openapi.yaml` | 说明改写后的地址形态与原因 |

---

## 五、验证

- backend：`PlaybackControllerTest` **6 passed**（含新增 3 个）
- backend 全量：**64 passed**，另有 6 个 `ApplicationStartupTest` 报错 ——
  `Communications link failure`，本机没有 MySQL。
  **改动前（485875d）同样报这 6 个错**，与本次改动无关
  （CI 里这两个用例是配了 MySQL/RabbitMQ 服务跑的，见 d3f0330）
- web：**31 passed**（含新增 11 个）
- web `vue-tsc --noEmit`：无报错

---

## 六、服务器上怎么收尾

```bash
cd /var/data/project/edam && git pull && sudo systemctl restart edam-backend
```

（后端改了代码，要重启；前端如果是 nginx 托管的静态文件，要重新构建并替换 dist。）

验证：

```bash
# 1. 看 m3u8 改写成了什么 —— 应该是 /api/v1/... 开头的根相对路径
#    （拿一个真实播放 token 直接打后端）
curl -s "http://127.0.0.1:8092/api/v1/playback/7/playlist.m3u8?token=<token>" | head -20
```

期望看到：

```
#EXT-X-KEY:METHOD=AES-128,URI="/api/v1/playback/7/key?token=..."
#EXTINF:10.0,
/api/v1/playback/7/segment/segment_000.ts?token=...
```

⚠️ 上面这条命令里的 token 要用**播放 token**（`POST /playback/7/token` 拿到的），
不是登录 token。

然后用 E000101 登录试播。如果还失败，看后端日志：

```bash
journalctl -u edam-backend -f -o cat | grep -E "m3u8_served|hls_key_served|segment_serve_failed"
```

现在这些行带 `requester_id`，能直接看出是谁的请求、哪个阶段失败。

---

## 七、遗留

1. **nginx 的 `X-Forwarded-Host` 建议改成 `$http_host`**（含端口）——
   这次改成根相对路径后已经不影响播放了，但其它依赖这个头的地方
   （比如审计日志里的真实 URL）还是会少端口。

2. **权限控制没做**：`video_permission` 表和 `batch-permission` 接口
   至今是 501 未实现（`VideoController.batchPermission`），
   也就是说**任何登录用户都能播任何视频**。这次故障之所以表现为
   "换个账号就不能播"，纯属 URL 猜错的副作用，不是权限逻辑。
   真要按密级/授权限制，得先把授权模型定下来（是否带有效期、
   能否再分享、被授权者能否下载）。

3. **K8s 侧要重建镜像**（后端代码改了）。
