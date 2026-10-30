# key.info 的行序：文档和 FFmpeg 4.3.9 不一致 —— 改成自适配

- 日期：2026-10-29（晚，接 `2026-10-29-fix-hls-key-info-format.md`）
- 触发：上一轮的修复没生效，同样的报错再来一次（video_id=5，21:06）

> ⚠️ **2026-10-30 订正**：本文的诊断**方向就是错的** ——
> 问题不是"行序"，而是**第二行的含义**。
>
> 真相：第二行是 **key 文件的路径**（FFmpeg 打开它，读前 16 字节），
> 不是 key 本身。官方文档里 "second line is the key" 的说法与实际行为不符。
>
> 所以本文试的两种布局**都不对**：
> - `uri_first`（第一行 URI、第二行十六进制）⇒ FFmpeg 拿十六进制当文件名
> - `key_first`（第一行十六进制、第二行 key.bin）⇒ FFmpeg 成功退出，
>   但把十六进制当 URI 写进了 m3u8 ⇒ **假成功** ⇒ 播放器拿不到密钥
>
> 完整分析见 `modify/2026-10-30-fix-hls-key-info-real-format.md`。
> 本文**唯一成立**的核心思路是"不赌，两种都试 + 校验产物"——
> 这个思路被保留了下来（并加上了启动时探测）。

---

## 一、上一轮的结论不完整（订正）

上一轮我判定根因是"key.info 第一行多了 `URI ` 前缀"，改成：

```
key.bin
42ccf808b90e06e674095a71f25f1106
```

（文档规定的「第一行 URI、第二行 key」）

**结果：还是同样的错。** FFmpeg 依然去打开那个十六进制：

```
[hls] Opening 'f37733de50726c6c2d7c2e3ee556c7e1' for reading
[hls muxer] error opening key file f37733de50726c6c2d7c2e3ee556c7e1
```

### 1.1 关键观察：两次失败打开的是**同一行**

| key.info 内容 | FFmpeg 试图打开的文件 |
|---|---|
| `URI key.bin` / `42ccf8…`（旧） | `42ccf8…`（**第 2 行**） |
| `key.bin` / `42ccf8…`（新） | `42ccf8…`（**第 2 行**） |

**不管第 1 行写什么，FFmpeg 都去开第 2 行。** ⇒ 它把**第 2 行**当成了
"key 文件的路径/URI"。

⇒ **FFmpeg 4.3.9 认的是「第一行 key、第二行 URI」** ——
**和文档写的正好相反。**

> 文档（ffmpeg-formats → `hls_key_info_file`）写的是：
> "first line is a URI to the key file / second line is the key"。
> 4.3.9 的实际行为与此相反。**各版本行为不一致，这是问题的本质。**

### 1.2 为什么不能靠"再查一次文档"解决

我想去读 FFmpeg 4.3.9 的 `hlsenc.c` 源码确认，但**这个环境的网络出口被封了**
（raw.githubusercontent / sources.debian / ffmpeg.org doxygen / gitee 全部
fetch 失败），只搜到片段、拿不到代码。

⇒ **不能再赌某一种写法**。服务器上到底哪个版本、哪种约定，以后还会变
（Debian 11 是 4.3.9、Debian 12 是 5.1、K8s 镜像里又是别的版本）。

---

## 二、修法：不赌，自适配 + 校验产物

### 2.1 两种布局都试

```
uri_first :  key.bin            ← 文档说的
             42ccf808b90e...

key_first :  42ccf808b90e...    ← FFmpeg 4.3.9 实测认的
             key.bin
```

试的顺序：**上次试出来的排第一**（记在 processor 实例上），
后续视频直接用它，不再白跑一次。

### 2.2 关键：**校验产物**，而不是"FFmpeg 退出码为 0 就算成功"

布局猜错时，FFmpeg 有可能**"成功"但把 key 的十六进制写进了 m3u8**：

```
#EXT-X-KEY:METHOD=AES-128,URI="42ccf808b90e06e674095a71f25f1106"
```

而**后端改写 m3u8 的正则是 `URI="key\.bin"`**（`PlaybackController:274`）——
URI 不对，后端改写不了 ⇒ 播放器拿不到密钥 ⇒ 视频解不开。

⇒ 所以成功的判据是两条：
1. FFmpeg 退出码为 0；
2. m3u8 里的 EXT-X-KEY URI **确实是 `key.bin`**。

第 2 条不满足 ⇒ 换另一种布局重试。

### 2.3 不该重试的错误不能被重试掩盖

只有"打不开 key 文件"这一类（`error opening key file` /
`Unable to open key file`）才换布局重试。
其它错误（输入文件坏了、编码器缺失、字体缺失）**立刻失败**，
否则真正的根因会被"重试"掩盖，最后报一个无关的错误。

### 2.4 顺带保留的两处修正（上一轮做的，仍然正确）

- **key.bin 提前写**：FFmpeg 会真的去打开它（实测
  `[hls] Opening '<key 文件>' for reading`），而原实现是转码**成功之后**
  才写 ⇒ FFmpeg 运行时那个文件不存在。内容是 16 字节原始 key，
  与后端下发给播放器的是同一份字节 ✓
- **FFmpeg 的 cwd 设为 output_dir**：key.info 里是相对路径 URI，
  而 FFmpeg 解析相对 URI 的基准目录各版本不一致（有的是 key.info
  所在目录，有的是进程 CWD）⇒ 两种解释都指向同一个文件。

---

## 三、改动清单

| 文件 | 改动 |
|---|---|
| `worker/src/edam_worker/processors/video.py` | `_write_key_info(output_dir, key, layout)` 按布局写；`_hls_transcode` 改成布局自适应循环 + 产物校验；新增 `_key_info_layouts` / `_playlist_key_uri_is_correct` / `_looks_like_key_info_layout_error` |
| `worker/tests/test_hls_keyinfo.py`（新，重写） | 9 个用例：key.bin 内容、两种布局的内容、"假成功"要能被发现、非布局错误不重试、记住的布局优先、FFmpeg 的 cwd |

---

## 四、验证

- worker 全量：44 passed, 2 skipped（本机无 PyPDF2 / ffmpeg）
- pyflakes：无 undefined name
- 新用例覆盖了三条关键行为：
  - 第一种布局报"打不开 key 文件" ⇒ 自动换第二种并成功
  - 转码"成功"但 URI 不对 ⇒ **不算成功**，换布局重试
  - 输入文件坏了 ⇒ **只试一次**，原始报错原样抛出

---

## 五、服务器上怎么收尾

```bash
cd /var/data/project/edam && git pull
sudo systemctl restart edam-worker
```

重跑失败的任务（重试用尽后消息已被丢弃）：

```bash
curl -X POST http://127.0.0.1:8001/ops/videos/5/reprocess-hls \
  -H "X-Ops-Token: <token>" -H "Content-Type: application/json" \
  -d '{"input_path": "<MinIO 里的 uploads/5/xxx.mp4>"}'
```

看日志确认它认哪种布局（第一次会先试 uri_first，失败后换 key_first）：

```bash
journalctl -u edam-worker -f -o cat | grep -E "hls_key_info_layout_rejected|hls_transcode_complete"
```

期望看到：

```
{"event": "hls_key_info_layout_rejected", "layout": "uri_first", ...}   ← 试第一种被拒
{"event": "hls_transcode_complete", "key_info_layout": "key_first", ...} ← 第二种成功
```

之后每条视频都会直接用 `key_first`（记住的），不再白跑。

⚠️ **回调那步还依赖 `WORKER_TOKEN` 两边一致** —— 那个坎还没过的话，
转码成功了状态也更新不了。

---

## 六、遗留 / 待办

1. **历史视频全是 pending** —— 2026-10-28 至今上传的都没转成过，
   需要逐个重新触发。
2. **`ffmpeg_probe.py` 保留** —— 它排除不了这次的根因（不是环境问题），
   但能一眼排除"环境缺能力"这一大类，值得留着。
3. **可以考虑把布局探测挪到启动时**（跑一次 1 秒的 lavfi 转码），
   这样第一个视频就不用先失败一次。当前是"失败一次就记住"，
   代价是每个 worker 进程的第一条视频多花一次 FFmpeg 启动时间。
4. **K8s 侧要重建镜像**。
