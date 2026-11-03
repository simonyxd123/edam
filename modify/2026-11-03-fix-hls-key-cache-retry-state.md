# 第二次播放报 `after key load, decryptdata unset or changed` —— 播放器的密钥账本失步

- 日期：2026-11-03
- 触发：上一版修复（`modify/2026-11-03-fix-hls-key-uri-rewrite.md`）上线后，
  用户反馈"**第一次播放视频正常，再次播放视频时**"报错：

  ```
  网络异常，正在重试（2/3）…（解密密钥 无响应（超时或连接被拒））：after key load, decryptdata unset or changed
  视频源不可达（解密密钥 无响应（超时或连接被拒））：密钥拉取失败，请查后端 key 端点、MinIO 里的 key.bin，
  以及播放列表里 EXT-X-KEY 的地址是否为空（hls.js：after key load, decryptdata unset or changed）
  ```

- 涉及方案书章节：**§4.2.1 切片与加密**（"播放列表（.m3u8）指向后端密钥获取接口"）、
  **§4.2.2 密钥接口加固**

---

## 一、这条报错到底是什么意思（它**不是**网络问题）

报错原文出自 hls.js 1.7.3 的 `src/loader/key-loader.ts`，
`loadKeyHTTP()` 里给密钥加载器配的 `onSuccess` 回调：

```ts
onSuccess: (response, stats, context, networkDetails) => {
  const { frag, keyInfo, url } = context;
  if (!frag.decryptdata || keyInfo !== this.keyLoaderInfo[url]) {
    return reject(createKeyLoadError(frag, ErrorDetails.KEY_LOAD_ERROR,
      new Error('after key load, decryptdata unset or changed'), networkDetails));
  }
  keyInfo.decryptdata.key = frag.decryptdata.key = new Uint8Array(response.data);
  ...
}
```

三条结论，逐条都能从源码读出来：

| 结论 | 依据 |
|---|---|
| **密钥的 HTTP 请求成功了** | 这段代码在 `onSuccess` 里 —— 只有 2xx 才会走到这里（`xhr-loader` 的 `readystatechange`：`status >= 200 && status < 300` 才调 `onSuccess`） |
| **失败的是 hls.js 自己的"账本"** | 判据是 `keyInfo !== this.keyLoaderInfo[url]`：`this.keyLoaderInfo` 是 hls.js 按密钥 URI 维护的缓存表（`keyLoaderInfo[uri] = {decryptdata, keyLoadPromise, loader}`）。请求回来时它发现"表里那条已经不是我这条" |
| **报错里没有 HTTP 响应** | `createKeyLoadError(...)` 这条分支**不传** `response`，所以前端只能显示"无响应（超时或连接被拒）" |

⇒ **和后端、MinIO、网络都无关**。密钥拿得好好的，是播放器自己记错了账。
⇒ 上一版提示里"请查后端 key 端点、MinIO 里的 key.bin"这条排查方向**是错的**。

### 1.1 账本为什么会错 —— `startLoad()` 的副作用

前端原来在致命网络错误时用 `hls.startLoad()` 重试。翻 hls.js 源码：

```
Hls.startLoad()
  └─ StreamController.startLoad()
       └─ this.stopLoad()            ← 先停
            ├─ this.fragmentLoader.abort()
            ├─ this.initFragmentLoader.abort()
            └─ this.keyLoader.abort(this.playlistType)   ← ★ 把在途的密钥请求中止掉
```

`KeyLoader.abort()` 走的是 `loader.abort()`（`xhr.abort()`），
**不会**触发 key-loader 的 `onAbort` 回调 —— 于是：

- `keyLoaderInfo[uri]` 这条记录**既没被删，也没有 promise 会 settle**（永远挂着）；
- 下一个分片再来取**同一个**密钥 URI 时，命中这条僵死记录，
  与真正发出去的新请求赛跑；
- 请求回来时 `keyInfo !== this.keyLoaderInfo[url]` ⇒ 抛
  `after key load, decryptdata unset or changed`。

**"第一次正常、再次出错"就是这么来的**：第一次播放没有失败，自然没有重试，
也就没有 `startLoad()`；一旦某次播放遇到一次可重试的网络抖动，
重试路径就会把 hls.js 的密钥账本搅坏，之后一路带着这条报错。

---

## 二、改了什么

### 2.1 前端：重试改成**重建播放器实例**，不再用 `startLoad()`

`web/src/views/VideoDetail.vue`

- 抽出 `createPlayer(src)`：建实例、挂 `<video>`、接事件、起播。
- 致命 `NETWORK_ERROR` 的重试 ⇒ **销毁旧实例 + 新建实例**（而不是 `startLoad()`）：
  - 新实例 = 新的 `KeyLoader` = **空的 `keyLoaderInfo` 账本**，从根上避开上面那个状态；
  - 顺带把旧实例挂着的请求全部放掉。
- **旧实例的事件一律忽略**：`if (hls.value !== instance) return`。
  原来没有这道闸 —— 旧实例迟到的 ERROR 事件会去动 `hls.value`（那时已经指向新实例了），
  把**新**实例的播放状态搅乱。
- **保留"首次（根因）错误"**：重试横幅与最终提示都以第一次的报错为准，
  次生错误不再覆盖它（否则用户永远看到的是次生错误，排查方向被带偏）。
- **重试计数提到组件作用域**：重建实例会让闭包里的计数器归零 ⇒ 会永远停在 `1/3`，
  变成无限重试。计数必须跨重建累计。

### 2.2 前端：这类报错要说清"不是后端的问题"

`web/src/utils/hlsError.ts`

- 识别 `after key load, decryptdata unset or changed` ⇒ 换成专门的提示：
  "播放器的密钥状态失步（密钥请求其实成功了，是 hls.js 内部缓存对不上）。已自动重建播放器重试……"
- 原来的 `keyLoadError` 提示（"查后端 key 端点、MinIO 里的 key.bin"）**不再**用于这一条 ——
  它会把运维引到完全错误的方向。

### 2.3 后端：key 端点的失败语义对齐 + 可观测性

`backend/.../controller/PlaybackController.java`

- **MinIO 不可达 ⇒ 503，不再报 404**（和 `getPlaylist` 同一个道理，之前只有播放列表改了，
  key 端点漏了）。报 404 会让运维以为"密钥文件被删了"，去查一个根本没坏的东西。
- 日志新增 `key_fp`（密钥 SHA-256 的前 8 个十六进制字符）与 `read_ms`（读耗时）：
  - `key_fp` 不是凭据（128 位随机密钥的单向摘要前缀，反推不出密钥本身），
    它的用处是**一眼看出两次播放拿到的是不是同一份密钥**
    （不一致 ⇒ 转码换过密钥 ⇒ 分片与密钥不匹配）；
  - `read_ms` 用来判断密钥请求是不是慢到会触发 hls.js 的加载超时。

---

## 三、验证

- 后端 `PlaybackControllerTest`：**15 个用例全过**（新增 4 个）
  - 密钥字节原样下发（`assertArrayEquals`）+ `Cache-Control: no-store`
  - MinIO 不可达 ⇒ **503**（不是 404）
  - 密钥长度不对 ⇒ 500（不把坏密钥发给播放器）
  - token 无效 ⇒ 401（不碰对象存储）
- 前端 `vitest`：**45 个用例全过**（新增 1 个：密钥状态失步那条必须说清不是后端问题）
- 前端 `vue-tsc --noEmit`：无类型错误

后端日志长这样：

```
hls_key_served, video_id=1, requester_id=3, key_fp=a1b2c3d4, read_ms=6
```

⇒ 两次播放的 `key_fp` 一致 ⇒ 两次拿到的是同一份密钥 ⇒ 可以排除"转码换过密钥"。

---

## 四、运维须知

1. **上线后先看一条 `hls_key_served ... key_fp=... read_ms=...`**：
   - `key_fp` 在两次播放之间变了 ⇒ 视频被重新转码过（密钥换了），此时旧的播放会话必然解不开；
   - `read_ms` 很大 ⇒ 密钥请求慢到会触发 hls.js 的加载超时，要查 MinIO。
2. **如果还看到 `after key load, decryptdata unset or changed`**：
   现在前端会自动重建播放器重试 3 次；仍然失败说明**第一次**失败另有原因 ——
   看浏览器控制台里的第一条 `[VideoDetail] HLS error details:` 日志，
   那一行是**根因**（重试横幅与最终提示现在都以它为准）。
3. 上一版的 `m3u8_served ... ext_x_key_uri=...` 日志继续有效 ——
   它回答的是"MinIO 里存的播放列表长什么样"。

## 五、没做什么（以及为什么）

- **没有给 key.bin 加内存缓存**：密钥在一个视频的生命周期内不变，
  但**重新转码会换密钥**。加了缓存就有"转码后短时间内下发旧密钥"的窗口，
  正确性风险大于性能收益。真要缓存，必须用对象存储的 ETag 做版本校验。
- **没有改 Worker 的 key.info 契约**：新产物本来就该是 `URI="key.bin"`，
  这次故障与 Worker 无关。
- **没有改 hls.js**：问题出在**我们怎么用它**（用 `startLoad()` 重试 + 没有旧实例闸门），
  改用法比改库安全。
