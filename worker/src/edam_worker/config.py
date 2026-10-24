"""
Worker 配置
通过环境变量覆盖
"""
import os
from dataclasses import dataclass


@dataclass
class Settings:
    # 运行时
    LOG_LEVEL: str = os.getenv("LOG_LEVEL", "INFO")
    HTTP_PORT: int = int(os.getenv("HTTP_PORT", "8001"))

    # 后端 API（Worker 处理完回调）
    BACKEND_BASE_URL: str = os.getenv("BACKEND_BASE_URL", "http://localhost:8092")

    # RabbitMQ
    RABBITMQ_HOST: str = os.getenv("RABBITMQ_HOST", "localhost")
    RABBITMQ_PORT: int = int(os.getenv("RABBITMQ_PORT", "5672"))
    RABBITMQ_USER: str = os.getenv("RABBITMQ_USER", "edam")
    RABBITMQ_PASSWORD: str = os.getenv("RABBITMQ_PASSWORD", "edampass")
    RABBITMQ_VHOST: str = os.getenv("RABBITMQ_VHOST", "/")

    # MinIO / S3
    MINIO_ENDPOINT: str = os.getenv("MINIO_ENDPOINT", "http://localhost:9000")
    MINIO_ACCESS_KEY: str = os.getenv("MINIO_ROOT_USER", "minioadmin")
    MINIO_SECRET_KEY: str = os.getenv("MINIO_ROOT_PASSWORD", "minioadmin")
    MINIO_BUCKET_VIDEOS: str = os.getenv("MINIO_BUCKET_VIDEOS", "edam-videos")
    MINIO_BUCKET_DOCS: str = os.getenv("MINIO_BUCKET_DOCS", "edam-documents")
    MINIO_BUCKET_WATERMARKS: str = os.getenv("MINIO_BUCKET_WATERMARKS", "edam-watermarks")

    # FFmpeg
    FFMPEG_PATH: str = os.getenv("FFMPEG_PATH", "/usr/bin/ffmpeg")
    FFMPEG_THREADS: int = int(os.getenv("FFMPEG_THREADS", "4"))
    FFPROBE_PATH: str = os.getenv("FFPROBE_PATH", "/usr/bin/ffprobe")

    # HLS
    HLS_SEGMENT_DURATION: int = int(os.getenv("HLS_SEGMENT_DURATION", "10"))
    HLS_PLAYLIST_TYPE: str = os.getenv("HLS_PLAYLIST_TYPE", "vod")
    # 注：原先这里有个 HLS_KEY_URL（默认 https://api.example.com/...），
    # 已删除 —— HLS 密钥现在由 worker 自己生成（AES-128 随机 key），
    # 存在 MinIO 的 videos/{id}/key.bin，由后端 getKey 端点按 token 下发。
    # 那个占位域名从来没有被真正使用过，留着只会误导。

    # 帧指纹
    FINGERPRINT_KEYFRAME_INTERVAL: int = int(os.getenv("FINGERPRINT_KEYFRAME_INTERVAL", "30"))


    # 监控
    ENABLE_METRICS: bool = os.getenv("ENABLE_METRICS", "true").lower() == "true"
    METRICS_PORT: int = int(os.getenv("METRICS_PORT", "9100"))

    # Worker 回调后端时携带的共享密钥
    # 后端 PATCH /videos/{id}/status 校验该头；为空则后端一律拒绝回调
    WORKER_TOKEN: str = os.getenv("WORKER_TOKEN", "")

    # 运维接口（按 video_id 重新触发 HLS 等）
    # OPS_TOKEN 为空时即便 OPS_ENABLED=true 也关闭（fail-closed）
    OPS_ENABLED: bool = os.getenv("OPS_ENABLED", "false").lower() == "true"
    OPS_TOKEN: str = os.getenv("OPS_TOKEN", "")

    # 任务处理失败时的内部重试（in-process，与 RabbitMQ requeue 无关）
    # 终极失败仍走 requeue=False 把消息丢了；这里只是给"瞬时错误"机会
    # WORKER_MAX_ATTEMPTS=1 = 关闭重试（与原先"一次性"语义一致）
    WORKER_MAX_ATTEMPTS: int = int(os.getenv("WORKER_MAX_ATTEMPTS", "3"))
    WORKER_RETRY_BASE_BACKOFF_SEC: float = float(os.getenv("WORKER_RETRY_BASE_BACKOFF_SEC", "1.0"))


settings = Settings()