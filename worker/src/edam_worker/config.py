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


settings = Settings()
