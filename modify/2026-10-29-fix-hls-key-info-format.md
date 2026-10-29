# HLS 转码全军覆没：key.info 的格式写错了一行

- 日期：2026-10-29（晚，接 `2026-10-29-worker-ffmpeg-failure-diagnosability.md`）
- 触发：上一轮把 FFmpeg 的 stderr 捞出来了 —— **真正的根因一眼可见**
- 影响：**每一条视频都转码失败**（2026-10-28 起就没人能成功转码）

---

## 一、上一轮的诊断错了（先说清楚）

上一轮记录里我把矛头指向"FFmpeg 环境缺能力"，列了四条候选：
缺 libx264 / 缺 aac / 缺 drawtext / 缺字体。

**四条全错。** 服务器上实测：

```
$ ffmpeg -hide_banner -encoders | grep -E "libx264|aac"
 V..... libx264              libx264 H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10 (codec h264)
 A..... aac                  AAC (Advanced Audio Coding)
$ ffmpeg -hide_banner -filters  | grep drawtext
 T.C drawtext          V->V       Draw text on top of video frames using libfreetype library.
```

编码器、滤镜、字体全在。**是代码错了，不是环境错了。**

> 教训：上一轮那个"能力自检"（`ffmpeg_probe.py`）本身没错 ——
> 它能把"环境缺能力"这类问题一眼排除掉。这次正是靠它排除了环境因素。
> 但**它只是排除法**，不能替代"把真正的报错捞出来看"。

---

## 二、根因：key.info 的格式

FFmpeg 的 stderr 里，这两行是全部答案：

```
[hls @ ...] Opening '/tmp/edam-video-3-x00de9sw/hls-l_r9g28a/key.info' for reading
[hls @ ...] Opening '42ccf808b90e06e674095a71f25f1106' for reading     ← ★ 它把 key 的 hex 当成了文件名
[hls muxer @ ...] error opening key file 42ccf808b90e06e674095a71f25f1106
Could not write header for output file #0 (incorrect codec parameters ?): No such file or directory
```

FFmpeg 打开了 key.info，然后**又去打开一个名叫「key 的十六进制」的文件** ——
那个文件当然不存在，于是转码失败。

### 2.1 我们写成了什么

```python
# 改之前
f.write("URI key.bin\n")      # ← 错
f.write(key.hex() + "\n")
```

文件内容：

```
URI key.bin
42ccf808b90e06e674095a71f25f1106
```

### 2.2 FFmpeg 规定的是什么

FFmpeg 文档（`ffmpeg-formats` → `hls_key_info_file`）：

> The file format is as follows:
> **first line is a URI to the key file**
> **second line is the key**
>
> The key is expected to be a 16-byte value formatted as 32 hexadecimal digits.

正确格式：

```
key.bin
42ccf808b90e06e674095a71f25f1106
```

**我们把文档里那句说明文字（"first line is a URI to the key file"）的标签
`URI ` 当成了文件内容的一部分写进去。**

⇒ FFmpeg 没把第一行当成 URI，于是拿第二行（key 的 hex）当文件名去打开
⇒ `No such file or directory` ⇒ **每一条视频都失败**。

### 2.3 为什么一直没被发现

1. **代码是照着"文档描述"写的，不是照着"文档示例"写的** ——
   描述读起来像是要写个 `URI ` 标签；
2. **没有任何测试碰过 key.info 的内容** —— 一行格式写错，全链路无声地废掉；
3. **错误信息被吞在日志里**（上一轮已修）。

---

## 三、顺带修掉的第二个隐患：key.bin 写得太晚

文档说第一行是 "URI to the **key file**" —— FFmpeg 完全可能真的去打开它。
而原实现是：

```python
# 改之前：转码**成功之后**才写 key.bin
with open(key_path, "wb") as f:
    f.write(key)
```

⇒ FFmpeg 运行期间，`key.bin` **压根不存在**。
即使 key.info 的格式修对了，只要 FFmpeg 会去打开那个 URI，还是会失败。

⇒ 现在 **key.bin 和 key.info 都在 FFmpeg 启动之前写好**。

### 3.1 再加一道保险：FFmpeg 的 CWD

key.info 里的 URI 是**相对路径**（`key.bin`），而 FFmpeg 解析相对 URI 的
基准目录**各版本不一致**（有的是 key.info 所在目录，有的是进程 CWD）。
⇒ 跑 FFmpeg 时把 **cwd 设成 output_dir**，两种解释都指向同一个文件。

（m3u8 里写进去的仍然是 `URI="key.bin"` 相对路径 —— 后端
`PlaybackController.rewriteM3u8` 靠正则 `URI="key\.bin"` 改写成带 token 的
绝对地址，改成绝对路径反而会让后端改写不了。）

---

## 四、改动清单

| 文件 | 改动 |
|---|---|
| `worker/src/edam_worker/processors/video.py` | key.info 第一行去掉 `URI ` 前缀；key.bin 提前写；FFmpeg 的 cwd 设为 output_dir；`_write_key_info` → `_write_key_files` |
| `worker/tests/test_hls_keyinfo.py`（新） | 4 个用例锁死 key.info 格式 / key.bin 时机 / m3u8 URI 形式 / FFmpeg cwd |

---

## 五、验证

- 新增 4 个用例：**对着旧代码 4 failed，对着新代码 4 passed** ✓
  （旧代码的 `_write_key_info` 连方法名都不一样，用例直接把它钉死）
- worker 全量：39 passed, 2 skipped（本机无 PyPDF2 / ffmpeg）
- pyflakes：无 undefined name
- 后端侧的配合关系已核对：
  - `PlaybackController:274` 的改写正则 `URI="key\.bin"` ⇒ m3u8 里必须是相对路径 ✓
  - `getSegment` 的白名单 `segment_\d+\.ts` ⇒ `hls/key.bin` 那份不会被代理暴露 ✓
  - 后端从 `videos/{id}/key.bin`（**不在 hls/ 下**）取密钥给播放器 ✓
  - worker `_upload_key` 用的是同一份 key 字节 ✓

---

## 六、服务器上怎么收尾

```bash
cd /var/data/project/edam && git pull
sudo systemctl restart edam-worker
```

重跑失败的任务（重试用尽后消息已被丢弃，需要重新触发）：

```bash
# 前端重新上传一次，或用 worker 的运维接口
curl -X POST http://127.0.0.1:8001/ops/videos/3/reprocess-hls \
  -H "X-Ops-Token: <token>" -H "Content-Type: application/json" \
  -d '{"input_path": "<MinIO 里的 uploads/3/xxx.mp4>"}'
```

验证：

```bash
journalctl -u edam-worker -f -o cat | grep -E "hls_transcode_complete|notify_backend_ok"
# 期望：hls_transcode_complete（转码成功）+ notify_backend_ok（回调成功）
```

⚠️ **回调那一步还依赖 `WORKER_TOKEN` 两边一致** ——
上一轮那个坎还没过的话，转码成功了状态也更新不了。

---

## 七、遗留

1. **历史视频全是 pending** —— 2026-10-28 到现在上传的视频都没转成过，
   需要逐个重新触发（或写个补跑脚本）。
2. **`ffmpeg_probe.py` 保留** —— 它虽然没抓到这次的根因，
   但确实能一眼排除"环境缺能力"这一大类问题，值得留着。
3. **K8s 侧要重建镜像**（改了 worker 代码）。
