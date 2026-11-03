# 播放失败：解密密钥拉不到 —— m3u8 里的 EXT-X-KEY 地址没被改写

- 日期：2026-11-03
- 触发：用户反馈播放视频时报
  `视频源不可达（解密密钥 无响应（超时或连接被拒））：密钥拉取失败，请查后端 key 端点与 MinIO 里的 key.bin`
- 涉及方案书章节：**§4.2.1 切片与加密**（"播放列表（.m3u8）指向后端密钥获取接口"）、
  **§4.2.2 密钥接口加固**

---

## 一、那条报错到底意味着什么（别被"网络异常"带偏）

前端提示里的两个词各有用处，但**合起来指向一个结论**：

| 提示词 | 来自哪里 | 含义 |
|---|---|---|
| `解密密钥` | `hlsError.ts` 的 `STAGE_BY_DETAILS['keyLoadError']` | hls.js 报的是 `details=keyLoadError` |
| `无响应（超时或连接被拒）` | `describePlaybackError` 里 `status` 不是数字 | **`data.response` 是 undefined** |

对 hls.js 1.7.3 逐条核对（`web/node_modules/hls.js/dist/hls.js`），
"key 加载失败 **且不挂 HTTP 响应**"只有四条路径：

| hls.js 源码位置 | 报错原文 | 本项目会不会命中 |
|---|---|---|
| `key-loader.js` `onError` | `HTTP Error <code> loading key <text>` | ❌ **会挂 response** ⇒ 前端会显示 `HTTP 404`/`HTTP 401` |
| `key-loader.js` `onTimeout` | `key loading timed out` | ❌ details 是 `keyLoadTimeOut`，提示语不同 |
| `key-loader.js` `loadKeyHTTP` | **`Invalid key URI: "<uri>"`** | ✅ **uri 为空时命中，且不挂 response** |
| `key-loader.js` `loadInternal` | `Missing decryption data…` | ❌ 需要 EME 开着（本项目没开） |

⇒ 用户看到的那条提示，**只可能**来自 `Invalid key URI`，
即 **hls.js 从播放列表里解析出的密钥地址是空的**，
它**压根没发过那个请求**（所以不是网络不通、不是超时、不是 404）。

`hls.js` 解析 EXT-X-KEY 的代码（`M3U8Parser.parseKey`）：

```js
// 若 URI 属性为空 ⇒ 解析结果是空字符串
var resolvedUri = decrypturi ? M3U8Parser.resolve(decrypturi, baseurl) : '';
```

### 1.1 空地址是怎么来的 —— 后端的改写正则太窄

`PlaybackController.rewriteM3u8` 原来只认**一种**写法：

```java
rewritten = rewritten.replaceAll(
    "URI=\"key\\.bin\"",
    String.format("URI=\"/api/v1/playback/%d/key?token=%s\"", videoId, token));
```

而 MinIO 里存量播放列表的 EXT-X-KEY 至少有以下几种形态
（都是这个项目自己历史上产出过的）：

| EXT-X-KEY 原文 | 怎么来的 | 老正则的结果 |
|---|---|---|
| `URI="key.bin"` | Worker 正常产物 | ✅ 改写成功 |
| `URI="42ccf808b90e06e674095a71f25f1106"` | 2026-10-29/30 key.info 的**"假成功"**（FFmpeg 退出码 0，但把 key 的十六进制当 URI 写进了 m3u8，见 `modify/2026-10-30-fix-hls-key-info-real-format.md`） | ❌ 不改写 |
| `URI="https://api.example.com/api/v1/playback/{id}/key"` | 更早的 Worker 把绝对地址烤进了 m3u8 | ❌ 不改写 |
| `URI=""` / 干脆没有 URI 属性 | 手工改过 / 别的工具产物 | ❌ 不改写 ⇒ **hls.js 拿到空地址 ⇒ 本次故障** |

最后一行就是本次线上故障的形态。

> 注意区分：前两种不改写 ⇒ hls.js 会去请求一个**错的**地址 ⇒
> 那是有 HTTP 响应的 ⇒ 前端会显示 `HTTP 404` / `HTTP 0`，**不是**本次这条提示。
> 只有"空地址"这一种才会产生"无响应（超时或连接被拒）"。

---

## 二、改了什么

### 2.1 后端：EXT-X-KEY 的 URI 改成"任何形态都换成后端 key 端点"

`backend/.../controller/PlaybackController.java`

- 新增 `rewriteKeyUris` / `rewriteKeyLine`：
  - **URI 已有值**（十六进制 / 绝对地址 / 空串，一视同仁）⇒ 换成
    `/api/v1/playback/{id}/key?token=...`
  - **压根没有 URI 属性** ⇒ 补一个
  - **`METHOD=NONE`** 的行**原样保留**（那一段本来就不加密）
  - 其它属性（`IV` / `KEYFORMAT` …）原样保留
- 分片地址的匹配放宽成 `^(segment_\d+\.ts)([ \t]*\r?)$`
  （老正则 `$` 严格，**CRLF 结尾的播放列表里的分片行压根没被改写过**）
- `replaceAll` 的替换串里，token 用 `Matcher.quoteReplacement` 包住
  （替换串里的 `$` / `\` 有特殊含义，别让凭据格式变化变成一次线上事故）
- **日志新增 `ext_x_key_uri` 字段**：打印 MinIO 里**原始**的 EXT-X-KEY 地址
  （token 已打码）。这是"MinIO 里到底存了什么"的唯一证据 ——
  以前不打出来，运维只能猜。

**关键点：改写发生在"下发播放列表"这一步 ⇒ 存量视频不用重转码、改 MinIO 里的 m3u8 就能修好。**

### 2.2 前端：把 hls.js 自己的失败原因原文透出来

`web/src/utils/hlsError.ts`、`web/src/views/VideoDetail.vue`

- `describePlaybackError` 新增 `reason` 字段 = `data.error.message`
- `formatPlaybackError` 把它附在文案末尾：
  `视频源不可达（解密密钥 无响应（超时或连接被拒））：……（hls.js：Invalid key URI: ""）`
- 重试横幅（`网络异常，正在重试（1/3）…`）也带上 reason

**为什么这一步不可省**：`data.response` 为 undefined 时，
前端只能显示"无响应"，而"请求压根没发出去"和"网络不通"
在状态码上**长得一模一样**。原文是唯一能区分二者的信息 ——
本次故障如果早有这一行，用户贴出来的第一手信息就是
`Invalid key URI`，而不是一句误导性的"超时或连接被拒"。

---

## 三、验证

- 后端 `PlaybackControllerTest`：**11 个用例全过**（新增 4 个）
  - 各种 EXT-X-KEY 形态都要被换成后端 key 端点（5 种形态）
  - `IV` 等其它属性要保留
  - `METHOD=NONE` 不许动
  - 多行 EXT-X-KEY 都要改
- 前端 `vitest`：**44 个用例全过**（新增 3 个）

后端日志长这样（`ext_x_key_uri` 就是原始地址）：

```
m3u8_served, video_id=1, ..., has_ext_x_key=true, ext_x_key_uri=key.bin, ...
m3u8_served, video_id=1, ..., has_ext_x_key=true, ext_x_key_uri=42ccf808b90e06e674095a71f25f1106, ...
m3u8_served, video_id=1, ..., has_ext_x_key=true, ext_x_key_uri=https://api.example.com/api/v1/playback/1/key, ...
m3u8_served, video_id=1, ..., has_ext_x_key=true, ext_x_key_uri=(空), ...
m3u8_served, video_id=1, ..., has_ext_x_key=true, ext_x_key_uri=(EXT-X-KEY 行里没有 URI 属性), ...
```

---

## 四、运维须知

1. **上线后先看一条日志**：`m3u8_served ... ext_x_key_uri=...`
   —— 它直接告诉你 MinIO 里那个播放列表的密钥地址长什么样。
2. 如果 `ext_x_key_uri` 是 `(空)` 或 `(EXT-X-KEY 行里没有 URI 属性)`，
   那就是本次故障的形态；后端改写后已经能正常播放，
   **但建议择机重转码**，让 MinIO 里的产物本身也是干净的
   （`ops/worker/reprocess-pending.sh` 目前只处理 `pending` 的视频）。
3. Worker 侧的契约（`worker/src/edam_worker/hls_keyinfo.py`：
   key.info 第 1 行必须是 `key.bin`）**保持不变** ——
   它是"新产物从源头就是对的"的保证，后端改写只是兜底。

## 五、没做什么（以及为什么）

- **没有改 Worker 的 key.info 契约**：新产物本来就该是 `URI="key.bin"`，
  放宽它反而会让"产物不干净"这件事更难发现。
- **没有写迁移脚本去批量修 MinIO 里的 m3u8**：改写发生在下发时，
  存量视频当场就能播；批量改对象存储反而多一份"改坏了就没法回滚"的风险。
