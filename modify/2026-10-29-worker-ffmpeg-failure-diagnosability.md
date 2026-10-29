# Worker 起起来了，但视频转码失败：把 FFmpeg 的诊断信息补上

- 日期：2026-10-29（晚）
- 触发：systemd 守护装好后，worker 正常跑起来了，**文档链路完全正常**
  （`document_processing_start` → `pdf_watermark_embedded` → `document_processing_complete`），
  但**视频链路失败**：

```
File ".../edam_worker/processors/video.py", line 357, in _hls_transcode
    raise RuntimeError(f"FFmpeg HLS 转码失败: {video_id}")
RuntimeError: FFmpeg HLS 转码失败: 3
```

---

## 一、问题：抛出来的异常里没有任何诊断信息

那行 `RuntimeError: FFmpeg HLS 转码失败: 3` 是**零信息**的 ——
它只说了"失败了"，没说"为什么失败"。

FFmpeg 自己其实把原因写进了 stderr，而 worker 也**确实**把它记到了日志里：

```python
log.error("ffmpeg_failed", returncode=..., cmd=..., stderr=...)
```

但这条**只在日志里**，不在异常里。而日志有两个问题：

1. **会被轮转** —— 出问题往往是一周后回头查，那时日志早没了；
2. **会被 grep 漏掉** —— 异常进队列、进告警、进任务重试记录，
   而 `ffmpeg_failed` 只是一行结构化日志。

⇒ 排查这个故障时，我只看到一句 RuntimeError，
**完全不知道是编码器缺失、滤镜缺失、字体缺失，还是输入文件本身是坏的**。

这类"错误信息本身没有信息"的坑，和前几轮修的那些是同一类：
审计日志写不进去、worker 静默成功、回调被拒不报原因 ——
**都是"出问题时你看不到为什么"**。

---

## 二、排查：哪些可能是真凶

### 2.1 排除项：上传/发布时序（没问题）

`VideoService.upload` 的顺序是：

```
1. 算 hash → 2. 秒传查询 → 3. **uploadToMinio(...) 真正传完**
→ 4. 查工号 → 5. persistAndPublish（落库 + 发 MQ）
```

⇒ MinIO 上传**先于**任务投递完成，worker 不可能下到半个文件。
**不是竞态**。

### 2.2 真正的候选：FFmpeg 环境

HLS 流水线需要三样东西：

| 需要 | 用来做什么 | 缺了会怎样 |
|---|---|---|
| `libx264` 编码器 | 视频转 H.264 | `Unknown encoder 'libx264'` |
| `aac` 编码器 | 音频转 AAC | `Unknown encoder 'aac'` |
| `drawtext` 滤镜 | 烧员工号水印（**防泄密的核心**） | `No such filter: 'drawtext'` |
| 字体文件 | drawtext 的 fontfile | drawtext 找不到字体直接失败 |

**Debian/Ubuntu 上这四条全都会踩坑**：

- 装的是**精简版 ffmpeg**（比如从别的源编译的、或某些云镜像自带的老版本）
  ⇒ 没有 libx264；
- `drawtext` 需要 ffmpeg 编译时带 **libfreetype**，
  且系统里装了**字体包**（`fonts-dejavu-core` / `fonts-liberation`）；
- 代码里的字体候选全是 `/usr/share/fonts/truetype/...` 这类**发行版路径**，
  精简镜像里一个都没有 ⇒ `font='DejaVu Sans'` 依赖 fontconfig，
  fontconfig 也没有 ⇒ 失败。

⚠️ **不能"绕过"水印来保转码成功** —— 水印是这套系统的核心价值
（方案书第 4 章防泄密），宁可转码失败也不能悄悄不烧水印。
所以正确做法是**把缺什么报出来**，而不是加个"没有字体就不加水印"的降级。

---

## 三、改动

### 3.1 异常里带上 FFmpeg 自己的报错 + 输入文件大小

`worker/src/edam_worker/processors/video.py`：

```python
raise RuntimeError(
    f"FFmpeg HLS 转码失败: {video_id}（exit={proc.returncode}，"
    f"输入 {input_size} 字节）\n"
    f"命令: {' '.join(cmd)}\n"
    f"FFmpeg 输出:\n{err[-2000:]}"
)
```

- **FFmpeg 输出尾部 2000 字符** —— 真正的原因（缺什么、哪一行）跟着异常走，
  进队列、进告警、进重试记录，日志轮转也不怕；
- **输入文件字节数** —— 一眼分辨两类问题：
  `0 字节 / 明显偏小` ⇒ 上传或下载坏了；
  `大小正常` ⇒ 基本就是 FFmpeg 环境的问题（编码器/滤镜/字体）；
- **完整命令行** —— 复现时不用靠猜。

### 3.2 worker 启动时做能力自检（新增 `ffmpeg_probe.py`）

把"环境对不对"从**第一个视频到来时**提前到**worker 启动时**：

```
{"event": "ffmpeg_capabilities_ok", "version": "ffmpeg version 5.1.6 ...", "font_path": "/usr/share/fonts/.../DejaVuSans-Bold.ttf"}
```

缺能力时：

```
{"event": "ffmpeg_capabilities_missing",
 "missing_encoders": ["libx264"],
 "missing_filters": ["drawtext"],
 "remediation": "缺少编码器 libx264 ⇒ 装完整的 ffmpeg：apt-get install -y ffmpeg（精简版 ffmpeg 不含 libx264） | ..."}
```

**刻意只报警告、不退出** —— 缺 FFmpeg 能力只是**视频**转不了，
文档水印链路照样能干活，为它把整个 worker 停掉不划算。

### 3.3 字体候选列表抽成共享模块（`fonts.py`）

启动自检和真正转码必须用**同一份**字体候选列表 ——
两边不一致的话，自检说"有字体"、转码时却找不到，排查方向就整个跑偏。

### 3.4 改动清单

| 文件 | 改动 |
|---|---|
| `worker/src/edam_worker/ffmpeg_probe.py`（新） | FFmpeg 能力探测 + 补救建议 |
| `worker/src/edam_worker/fonts.py`（新） | 共享的字体候选列表 |
| `worker/src/edam_worker/processors/video.py` | 异常带 FFmpeg 输出 + 输入大小；字体改用共享列表 |
| `worker/src/edam_worker/main.py` | 启动时做能力自检 |
| `worker/tests/test_ffmpeg_probe.py`（新） | 10 个用例（真实 FFmpeg 输出格式的解析 + 缺能力的报法） |

---

## 四、验证

- worker 全量：35 passed, 2 skipped（本机无 PyPDF2 / ffmpeg）
- pyflakes：无 undefined name
- 手工启动 worker：自检确实执行了 —— 本机没 ffmpeg，输出
  `ffmpeg_capabilities_missing` + "FFmpeg 不可执行" + 补救提示，
  且**worker 没有因此退出**（文档链路不受影响）✓
- 新增 10 个用例覆盖：输出格式解析、缺编码器、缺滤镜、缺字体、
  二进制不存在、超时 → 都能给出明确原因

---

## 五、服务器上怎么定位并修

### 5.1 先把**已经记录下来的** stderr 捞出来（不用等下一次失败）

```bash
journalctl -u edam-worker --since "17:05" --no-pager -o cat | grep ffmpeg_failed | tail -1 | python3 -m json.tool
```

（`ffmpeg_failed` 是 structlog 的 JSON 行，`-o cat` 去掉 journald 前缀）

### 5.2 拉新代码后，worker 启动时自检会直接告诉你缺什么

```bash
cd /var/data/project/edam && git pull
sudo systemctl restart edam-worker
journalctl -u edam-worker -n 20 --no-pager -o cat | grep ffmpeg_capabilities
```

### 5.3 按自检结果修（Debian/Ubuntu）

```bash
apt-get install -y ffmpeg fonts-dejavu-core fonts-liberation
ffmpeg -hide_banner -encoders | grep -E "libx264|aac"
ffmpeg -hide_banner -filters  | grep drawtext
ls /usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf
```

三条都该有输出。缺 `drawtext` ⇒ 装的 ffmpeg 没编 freetype，
要换一个带 freetype 的构建（Debian 官方包是带的）。

### 5.4 重跑失败的那条任务

```bash
# 队列里的消息在重试用尽后已被丢弃，需要重新触发
# （worker 的运维接口，默认关闭：OPS_ENABLED=true + OPS_TOKEN）
curl -X POST http://127.0.0.1:8001/ops/videos/3/reprocess-hls \
  -H "X-Ops-Token: <token>" -H "Content-Type: application/json" \
  -d '{"input_path": "<MinIO 里的 uploads/3/xxx.mp4>"}'
```

或者前端重新上传一次。

---

## 六、遗留

1. **失败的临时文件会被清掉**（`finally: _cleanup_temp(work_dir)`），
   事后没法拿原始输入文件复核 —— 本次靠"输入字节数"这个间接线索判断。
   要保留现场的话得改成"失败时保留 + 定期清理"，本次未做。
2. **worker 目前不区分"视频能力缺失"和"视频本身坏"** ——
   自检只能说明环境对不对，输入文件坏不坏还是得看 FFmpeg 的 stderr。
3. **K8s 侧同样要重建镜像**（新增了 `ffmpeg_probe.py` / `fonts.py`）。
