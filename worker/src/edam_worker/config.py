"""
Worker 配置
通过环境变量覆盖
"""
import os
from dataclasses import dataclass, field


def _env(key: str, default):
    """读环境变量（带类型转换）。

    **为什么不用 dataclass 字段默认值直接写 os.getenv(...)**：
    字段默认值是**类定义时**求值一次并冻结 —— 也就是 `import config`
    那一刻的环境变量快照。之后再改环境变量（monkeypatch、K8s secret
    轮换、热更新）**完全无效**。

    改用 default_factory 后，每次实例化都重新读环境变量，语义正确，
    单例 `settings` 的行为也保持不变（它仍在 import 时创建一次）。
    """
    raw = os.getenv(key)
    if raw is None:
        return default
    if isinstance(default, bool):
        return raw.strip().lower() in ("true", "1", "yes", "on")
    if isinstance(default, int):
        return int(raw)
    if isinstance(default, float):
        return float(raw)
    return raw


@dataclass
class Settings:
    # 运行时
    LOG_LEVEL: str = field(default_factory=lambda: _env("LOG_LEVEL", "INFO"))
    HTTP_PORT: int = field(default_factory=lambda: _env("HTTP_PORT", 8001))

    # 后端 API（Worker 处理完回调）
    BACKEND_BASE_URL: str = field(
        default_factory=lambda: _env("BACKEND_BASE_URL", "http://localhost:8092"))

    # RabbitMQ
    RABBITMQ_HOST: str = field(default_factory=lambda: _env("RABBITMQ_HOST", "localhost"))
    RABBITMQ_PORT: int = field(default_factory=lambda: _env("RABBITMQ_PORT", 5672))
    RABBITMQ_USER: str = field(default_factory=lambda: _env("RABBITMQ_USER", "edam"))
    RABBITMQ_PASSWORD: str = field(default_factory=lambda: _env("RABBITMQ_PASSWORD", "edampass"))
    RABBITMQ_VHOST: str = field(default_factory=lambda: _env("RABBITMQ_VHOST", "/"))

    # MinIO / S3
    MINIO_ENDPOINT: str = field(default_factory=lambda: _env("MINIO_ENDPOINT", "http://localhost:9000"))
    MINIO_ACCESS_KEY: str = field(default_factory=lambda: _env("MINIO_ROOT_USER", "minioadmin"))
    MINIO_SECRET_KEY: str = field(default_factory=lambda: _env("MINIO_ROOT_PASSWORD", "minioadmin"))
    MINIO_BUCKET_VIDEOS: str = field(default_factory=lambda: _env("MINIO_BUCKET_VIDEOS", "edam-videos"))
    MINIO_BUCKET_DOCS: str = field(default_factory=lambda: _env("MINIO_BUCKET_DOCS", "edam-documents"))
    MINIO_BUCKET_WATERMARKS: str = field(
        default_factory=lambda: _env("MINIO_BUCKET_WATERMARKS", "edam-watermarks"))

    # FFmpeg
    FFMPEG_PATH: str = field(default_factory=lambda: _env("FFMPEG_PATH", "/usr/bin/ffmpeg"))
    FFMPEG_THREADS: int = field(default_factory=lambda: _env("FFMPEG_THREADS", 4))
    FFPROBE_PATH: str = field(default_factory=lambda: _env("FFPROBE_PATH", "/usr/bin/ffprobe"))

    # HLS
    HLS_SEGMENT_DURATION: int = field(default_factory=lambda: _env("HLS_SEGMENT_DURATION", 10))
    HLS_PLAYLIST_TYPE: str = field(default_factory=lambda: _env("HLS_PLAYLIST_TYPE", "vod"))
    # 注：原先这里有个 HLS_KEY_URL（默认 https://api.example.com/...），
    # 已删除 —— HLS 密钥现在由 worker 自己生成（AES-128 随机 key），
    # 存在 MinIO 的 videos/{id}/key.bin，由后端 getKey 端点按 token 下发。
    # 那个占位域名从来没有被真正使用过，留着只会误导。

    # 帧指纹
    FINGERPRINT_KEYFRAME_INTERVAL: int = field(
        default_factory=lambda: _env("FINGERPRINT_KEYFRAME_INTERVAL", 30))

    # 监控
    ENABLE_METRICS: bool = field(default_factory=lambda: _env("ENABLE_METRICS", True))
    METRICS_PORT: int = field(default_factory=lambda: _env("METRICS_PORT", 9100))

    # Worker 回调后端时携带的共享密钥
    # 后端 PATCH /videos/{id}/status 校验该头；为空则后端一律拒绝回调
    WORKER_TOKEN: str = field(default_factory=lambda: _env("WORKER_TOKEN", ""))

    # 运维接口（按 video_id 重触发 HLS）
    OPS_ENABLED: bool = field(default_factory=lambda: _env("OPS_ENABLED", False))
    OPS_TOKEN: str = field(default_factory=lambda: _env("OPS_TOKEN", ""))

    # 任务失败重试
    WORKER_MAX_ATTEMPTS: int = field(default_factory=lambda: _env("WORKER_MAX_ATTEMPTS", 3))
    WORKER_RETRY_BASE_BACKOFF_SEC: float = field(
        default_factory=lambda: _env("WORKER_RETRY_BASE_BACKOFF_SEC", 1.0))

    # 回调后端的重试（跟任务重试是两回事）：
    # 任务重试 = 整个流水线重跑（下载 + 转码，很贵）；
    # 回调重试 = 只重发那个 PATCH（很便宜）。
    # 瞬时故障（后端重启 / 网络闪断）先用便宜的回调重试顶一下。
    WORKER_CALLBACK_MAX_ATTEMPTS: int = field(
        default_factory=lambda: _env("WORKER_CALLBACK_MAX_ATTEMPTS", 3))
    WORKER_CALLBACK_RETRY_BACKOFF_SEC: float = field(
        default_factory=lambda: _env("WORKER_CALLBACK_RETRY_BACKOFF_SEC", 2.0))

    # ----------------------------------------------------------------
    # 盲水印密码（embed 与 extract 共用）
    #
    # blind-watermark 的 password_wm 决定 DCT 系数带的选取/打乱方式，
    # 它不是"密钥"而是"算法参数" —— 但它同时是**提取的门槛**：
    # 没有正确的密码就提取不出水印。
    #
    # 2026-11-14 代码审计第六轮：此前三处（embed_image / embed_pdf 走的水印、
    # _extract_image、以及 DocWatermarkService）**全部硬编码 1**。
    # ⇒ 任何拿到开源 blind-watermark 库的人都能：
    #   - 从泄露文件里提取出指纹（直接知道是谁传的）
    #   - 自己往文件里嵌入任意指纹（伪造溯源）
    #   - 用错误指纹覆盖掉真指纹（抹除溯源）
    # 门槛为零 = 这层"水印"只挡不懂行的人。
    #
    # 取值范围：整数 1..999（blind-watermark 的约束）。
    # 默认值仍是 1 —— **为了兼容本功能上线前已经嵌入的水印**；
    # 提取时会同时尝试"配置的密码"与"1"，两者都取不到才算提取失败。
    # 生产环境应当通过环境变量注入一个非默认值。
    # ----------------------------------------------------------------
    EDAM_WATERMARK_PASSWORD: int = field(
        default_factory=lambda: _env("EDAM_WATERMARK_PASSWORD", 1))

    # ----------------------------------------------------------------
    # 外部子进程的超时（FFmpeg / ffprobe）
    #
    # 为什么必须有：这两个子进程原先是**裸 await proc.communicate()**，
    # 没有超时。一个 pathological 的输入（损坏的文件、卡死的网络流、
    # FFmpeg 自己死锁）会让协程**永远不返回** ——
    # 而 prefetch_count=10 ⇒ 10 个卡住的任务就把整个 worker 堵死，
    # 队列不再消费，而且没有任何异常、没有任何日志（进程还"活着"，
    # 健康检查照样返回 ok）。
    #
    # 超时之后要**杀掉子进程**，否则它继续占着 CPU 和内存。
    # ----------------------------------------------------------------
    FFMPEG_TIMEOUT_SEC: float = field(
        default_factory=lambda: _env("FFMPEG_TIMEOUT_SEC", 3600.0))
    FFPROBE_TIMEOUT_SEC: float = field(
        default_factory=lambda: _env("FFPROBE_TIMEOUT_SEC", 120.0))
    # LibreOffice headless 转 PDF（docx/xlsx → pdf）
    LIBREOFFICE_TIMEOUT_SEC: float = field(
        default_factory=lambda: _env("LIBREOFFICE_TIMEOUT_SEC", 600.0))

    # ----------------------------------------------------------------
    # 超时预算：三个时间必须**从大到小**排成一条链
    #
    #   RABBITMQ_CONSUMER_TIMEOUT_SEC   （broker 侧的"未确认投递超时"）
    #     > WORKER_TASK_DEADLINE_SEC     （worker 侧"整个任务"的墙钟上限）
    #       > FFMPEG_TIMEOUT_SEC         （单个 FFmpeg 子进程的上限）
    #
    # 为什么必须这样（2026-11-10 事故）：
    # RabbitMQ 有一个未确认投递超时（consumer_timeout，**默认 30 分钟**）。
    # 消息投递后超过这个时间还没 ack，broker 会**掐断 channel**
    # （PRECONDITION_FAILED: delivery acknowledgement on channel timed out），
    # 正在跑的转码任务被连带取消 —— 协程收到 CancelledError。
    #
    # CancelledError 在 Python 3.8+ 继承的是 **BaseException**，
    # 所以 `except Exception` **抓不到它** ⇒
    #   - 没有 video_processing_failed 日志
    #   - Prometheus 计数器不动
    #   - /health/worker 里的 last_failure_* 也不更新
    # ⇒ 一次"被 broker 掐死"的转码，在监控上**完全看不见**。
    #
    # worker 自己的任务上限必须**小于** broker 的未确认超时，
    # 这样超时由 worker 主动触发：拿到一个干净的、可重试的、
    # 已经通知过后端的失败，而不是被 broker 从背后掐断。
    # ----------------------------------------------------------------
    WORKER_TASK_DEADLINE_SEC: float = field(
        default_factory=lambda: _env("WORKER_TASK_DEADLINE_SEC", 10800.0))
    # **必须与 RabbitMQ 侧实际配置的 consumer_timeout 保持一致**。
    # broker 侧怎么配见 ops/rabbitmq/README.md（默认 4 小时 = 14400s）。
    RABBITMQ_CONSUMER_TIMEOUT_SEC: float = field(
        default_factory=lambda: _env("RABBITMQ_CONSUMER_TIMEOUT_SEC", 14400.0))


settings = Settings()


def timeout_budget_warnings() -> list:
    """检查超时预算是否自洽，返回告警列表（空 = 一切正常）。

    **为什么要有这个自检**：三个超时值分布在三个地方 ——
    worker 的环境变量、RabbitMQ 的配置、代码里的默认值。
    任何一处漂移，后果都是"broker 先掐断 channel"，
    而那种失败的现场在监控上**完全看不见**（见 Settings 里的说明）。

    所以启动时必须喊出来：现在的配置**会不会**被 broker 掐断。

    顺序要求（从大到小）：
        RABBITMQ_CONSUMER_TIMEOUT_SEC > WORKER_TASK_DEADLINE_SEC > FFMPEG_TIMEOUT_SEC
    """
    warnings = []
    broker = settings.RABBITMQ_CONSUMER_TIMEOUT_SEC
    task = settings.WORKER_TASK_DEADLINE_SEC
    ffmpeg = settings.FFMPEG_TIMEOUT_SEC

    if task >= broker:
        warnings.append(
            f"WORKER_TASK_DEADLINE_SEC({task:.0f}s) >= "
            f"RABBITMQ_CONSUMER_TIMEOUT_SEC({broker:.0f}s)："
            f"broker 会先掐断 channel，转码任务被连带取消，"
            f"且监控上看不到任何失败记录。"
            f"请把 worker 的任务上限调小，或把 broker 的 consumer_timeout 调大"
            f"（见 ops/rabbitmq/README.md）。"
        )
    if ffmpeg >= task:
        warnings.append(
            f"FFMPEG_TIMEOUT_SEC({ffmpeg:.0f}s) >= "
            f"WORKER_TASK_DEADLINE_SEC({task:.0f}s)："
            f"单个 FFmpeg 的超时永远不会触发 —— 任务级上限会先把它掐掉，"
            f"于是永远拿不到 'FFmpeg 转码超时' 这个可诊断的错误。"
        )
    return warnings


# 后端的 context-path（server.servlet.context-path）
_API_PREFIX = "/api/v1"


def backend_api_url(path: str) -> str:
    """拼后端 API 的完整 URL —— **兼容 BACKEND_BASE_URL 带不带 context-path**。

    为什么需要这个函数：

    `BACKEND_BASE_URL` 这个环境变量在仓库里一直有两种写法 ——

    - `worker/src/edam_worker/config.py` 的默认值：`http://localhost:8092`（不带）
    - `helm/edam/values.yaml` 与 `ops/worker/edam-worker.env.example`：
      `http://edam-backend:8092/api/v1`（**带**）

    而回调代码又习惯性地拼上 `/api/v1/...`，于是 Helm 部署下拼出来的是

        http://edam-backend:8092/api/v1/api/v1/videos/1/status

    → 404 → 被当成"其它 4xx" → `PermanentWorkerError` → **视频状态永远不回写**，
    转码明明完成了，`hls_status` 却一直停在 pending，用户点播放还是 404。

    这个坑在 `main.py` 的健康探测里已经用"两个候选都试"绕过去了，
    但回调处没跟上。这里统一收口：去掉 base 末尾的 `/api/v1`（如果有），
    再统一拼一次 —— 两种写法都对。

    :param path: 相对 API 路径，如 `videos/1/status`
    :return: 完整 URL
    """
    base = settings.BACKEND_BASE_URL.rstrip("/")
    if base.endswith(_API_PREFIX):
        base = base[: -len(_API_PREFIX)]
    return f"{base}{_API_PREFIX}/{path.lstrip('/')}"
