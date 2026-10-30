# key.info 的第二行不是 key —— 是 key 文件的路径（我连错两次的最终答案）

- 日期：2026-10-30（接 `2026-10-29-fix-hls-key-info-format.md` 与
  `2026-10-29-fix-hls-key-info-layout-adaptive.md`）
- 触发：自适应布局上线后，服务器日志里出现**两条一模一样的** `uri_first` 被拒记录

---

## 一、这次真正查到了什么

前两轮我改了两次格式，两次都错。这次查到了 FFmpeg 邮件列表归档
（[2015 年 HLS 加密补丁的讨论](https://ffmpeg.org/pipermail/ffmpeg-devel/2015-January/167436.html)），
看到了**原始补丁作者给的示例**：

```sh
openssl rand 16 > file.key
echo $BASE_URL/file.key > file.keyinfo        # 第 1 行：key 的 URI
echo file.key >> file.keyinfo                 # 第 2 行：key 文件的**路径**
echo $(openssl rand -hex 16) >> file.keyinfo   # 第 3 行：IV（可选）
```

⇒ **key.info 的真实格式**：

| 行 | 含义 |
|---|---|
| 第 1 行 | key 的 **URI** —— 写进 m3u8 的 `#EXT-X-KEY` |
| 第 2 行 | **key 文件的路径** —— FFmpeg 打开它，读前 16 字节 |
| 第 3 行 | 可选 IV（32 位十六进制） |

**第二行从来不是 key 本身。**

### 1.1 官方文档为什么把我带偏了

`ffmpeg-formats` 里 `hls_key_info_file` 写的是：

> first line is a URI to the key file, second line is the key.
> The key is expected to be a 16-byte value formatted as 32 hexadecimal digits.

**这段描述与实际行为不符** —— 它说的"second line is the key"，
实际是"second line is the path to the key file"。
同一个补丁的作者在邮件列表里给的示例才是准的。

### 1.2 用服务器日志反推，结论完全吻合

| key.info 内容 | FFmpeg 试图打开的文件 |
|---|---|
| `URI key.bin` / `42ccf8…`（第一版） | `42ccf8…`（**第 2 行**） |
| `key.bin` / `42ccf8…`（第二版） | `42ccf8…`（**第 2 行**） |
| `42ccf8…` / `key.bin`（第三版） | `key.bin` ✅ **打开成功** |

第三行是决定性证据：FFmpeg 打开第 2 行指定的文件并从中读取 key。
我前两版都把十六进制放在了第 2 行 ⇒ FFmpeg 拿它当**文件名** ⇒
`No such file or directory` ⇒ 每一条视频都失败。

---

## 二、为什么第三版（key_first）也没成 —— 它其实是**假成功**

第三版把 `key.bin` 放到了第 2 行。FFmpeg 打开成功了，但：

- 第 1 行是 `42ccf8…` ⇒ FFmpeg 把**它**当 URI 写进了 m3u8
- ⇒ m3u8 里是 `URI="42ccf808b90e06e674095a71f25f1106"`
- ⇒ FFmpeg **退出码 0**

看起来"转码成功了"，但后端改写正则是 `URI="key\.bin"`
（`PlaybackController:274`）⇒ 改写不成立 ⇒ 播放器拿不到密钥 ⇒ 视频解不开。

这正是我上一轮加的 `_playlist_key_uri_is_correct` 校验拦下来的。

### 2.1 这次日志为什么是两条一样的 `uri_first`

服务器日志：

```
01:00:26  hls_key_info_layout_rejected  layout=uri_first
01:00:36  hls_key_info_layout_rejected  layout=uri_first
```

两条都是 `uri_first`，间隔 10 秒 —— 正好是**重试**的节奏：

1. 第一次尝试：`uri_first`（第 2 行 = 十六进制）→ FFmpeg 打开失败 → 记录拒绝
2. 换 `key_first`（第 2 行 = `key.bin`）→ FFmpeg 成功，但 URI 不对 → 判"假成功"
3. 抛异常 → 任务重试
4. 重试时 `_key_info_layout` 没被记住（因为从没真正成功过）→ **又从 `uri_first` 开始**

⇒ 每条视频都要白跑两轮 FFmpeg，然后失败。

---

## 三、这次怎么改

### 3.1 正确的写法

```
key.bin
/tmp/edam-video-7-xxxx/hls-yyyy/key.bin     ← key 文件的绝对路径
```

- 第 1 行必须是 `key.bin` ⇒ m3u8 里就是 `URI="key.bin"` ⇒ 后端能改写
- 第 2 行用**绝对路径** ⇒ FFmpeg 解析相对路径的基准目录各版本不一致
  （有的是 key.info 所在目录，有的是进程 CWD），绝对路径绕开这个差异

### 3.2 但仍然要两种写法都支持

各版本对第 2 行的处理不一致（服务器 4.3.9 要路径，新版文档说要是十六进制），
所以不赌 —— 两种都试，并且**校验产物**。

### 3.3 新增：启动时探测一次

上一轮遗留里提过"把布局探测挪到启动时"，这次做了：

用一段 **1 秒的合成视频**（`lavfi testsrc`）试每种写法，看哪种能产出
`URI="key.bin"` 的 m3u8。

- 不需要真实素材、不需要 drawtext 字体 ⇒ 很快（~1 秒）、不污染业务数据
- 探测结果写进 `VideoProcessor` ⇒ 后续每条视频直接用对的写法
- **不用再"每条视频先失败一次再换一种"**
- 探测失败也不致命 ⇒ 转码时仍会逐个试两种写法

判据是**产物**而不是退出码 —— 只看退出码会踩上面说的"假成功"。

---

## 四、改动清单

| 文件 | 改动 |
|---|---|
| `worker/src/edam_worker/hls_keyinfo.py`（新） | key.info 格式契约：写 key.bin / key.info、校验 m3u8 的 URI、错误分类、**启动探测** |
| `worker/src/edam_worker/processors/video.py` | 改用 `hls_keyinfo` 模块；新增 `set_key_info_layout()`；删除重复的实现 |
| `worker/src/edam_worker/main.py` | 启动时调 `probe_key_info_layout()`，结果写进日志并记住 |
| `worker/tests/test_hls_keyinfo.py`（重写） | 9 个用例：格式内容、key.bin 时机、假成功、错误分类不误判 |
| `worker/tests/test_hls_keyinfo_module.py`（新） | 17 个用例：模块契约、探测的留痕与短路 |
| `ops/worker/reprocess-pending.sh`（新） | 批量补跑脚本：从库里捞 pending 的视频，逐条重新入队 |

---

## 五、验证

- worker 全量：**66 passed, 2 skipped**（本机无 PyPDF2 / ffmpeg）
- pyflakes：无 undefined name
- 错误分类器用 9 个真实报错串验证：
  - 会被识别成"布局问题"：`error opening key file` / `Unable to open key file` /
    `error reading key file` / `Invalid key size` / `Invalid hex`
  - **不会**被误判：输入文件损坏、编码器缺失、输出流初始化失败
    （这些必须原样抛出，不能被"换写法重试"掩盖）

---

## 六、服务器上怎么收尾

```bash
cd /var/data/project/edam && git pull && sudo systemctl restart edam-worker
```

**启动日志会直接告诉你本机认哪种**：

```bash
journalctl -u edam-worker -n 50 -o cat | grep -E "hls_key_info_layout|capabilities"
```

期望：

```json
{"event": "hls_key_info_layout_detected", "layout": "uri_keyfile", ...}
```

（`uri_keyfile` = 第 2 行是 key 文件路径；`uri_hex` = 第 2 行是十六进制）

如果看到 `hls_key_info_layout_unknown`，日志里的 `tried[].output_tail`
就是 FFmpeg 的原话，直接能看出卡在哪。

然后重跑卡住的视频：

```bash
curl -X POST http://127.0.0.1:8001/ops/videos/7/reprocess-hls \
  -H "X-Ops-Token: <token>" -H "Content-Type: application/json" \
  -d '{"input_path": "<MinIO 里的 uploads/7/xxx.mp4>"}'
```

看结果：

```bash
journalctl -u edam-worker -f -o cat | grep -E "hls_transcode_complete|notify_backend"
```

期望 `hls_transcode_complete` 且带 `key_info_layout=uri_keyfile`。

### 6.1 批量补跑（历史视频全是 pending）

2026-10-28 起上传的视频**一条都没转成过**（worker 一直没起来，
重试用尽后消息被 ACK 丢弃 ⇒ 队列里不会再有它们 ⇒ 必须主动重触发）。

逐条手工 curl 十几条太累，也容易漏 ⇒ 加了个脚本：

```bash
# 先干跑，看清楚要处理哪些
sudo ./ops/worker/reprocess-pending.sh

# 确认无误后真的入队
sudo ./ops/worker/reprocess-pending.sh --execute
```

脚本做的事：
1. 从 `/etc/edam/worker.env` 读 `OPS_TOKEN`（不走命令行 —— 免得进 shell history）
2. 检查 worker 的 ops 端点活着、`OPS_ENABLED=true`
3. 从 MySQL 捞 `hls_status <> 2`（非 ready）且 `minio_path` 非空的视频
4. 逐条 POST 到 worker 的 `/ops/videos/{id}/reprocess-hls`
   （走原 RabbitMQ 队列 ⇒ 水印/加密/上传/回调都和首次 upload 完全一致）

**已用假的 mysql/curl 测过四条路径**：干跑、入队成功、入队失败、
ops 未开启。测试中发现并修掉一个真 bug ——
`grep -o '"ops_enabled":[^,]*'` 在 `ops_enabled` 是 JSON 最后一个字段时
会切出 `true}`，导致**明明开着却被判成关闭**。

⚠️ **`OPS_ENABLED` 默认是 `false`** —— 要用这个脚本，得先在
`/etc/edam/worker.env` 里设 `OPS_ENABLED=true` + `OPS_TOKEN=...`
再重启 worker。

⚠️ **`WORKER_TOKEN` 两边一致这个坎还没过** —— 转码成功了状态也更新不了，
视频照样播不了。验证：

```bash
curl -s http://127.0.0.1:8092/api/v1/health/ready | python3 -m json.tool | grep -A4 worker_callback
```

---

## 七、遗留

1. **K8s 侧要重建镜像**（改了 worker 代码）。
2. **本地验证的缺口**：本机没装 FFmpeg，探测逻辑是用假的 subprocess 测的
   （只验证了"探测函数怎么用"，没验证"真 FFmpeg 的产物对不对"）。
   真正的验证在服务器上 —— 启动探测会跑真的 FFmpeg，
   结果直接写进 `hls_key_info_layout_detected` 日志。

---

## 八、服务器实测结果（2026-10-30）

```
{"event": "ffmpeg_capabilities_ok",
 "version": "ffmpeg version 4.3.9-0+deb11u2", ...}

{"event": "hls_key_info_layout_detected", "layout": "uri_keyfile",
 "hint": "key.info 第一行=key 的 URI，第二行=key 文件的路径（不是 key 本身）"}
```

⇒ **探测确认了诊断**：这台机器的 FFmpeg 4.3.9 认的正是
"第二行 = key 文件的路径"。探测耗时 200ms。

⇒ 前两版（`uri_first` / `key_first`）都必然失败 —— 现在可以确定地说，
不是"文档和实现不一致"，而是**文档本身写错了**：
`second line is the key` 实际是 `second line is the path to the key file`。
