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

    # HLS
    HLS_SEGMENT_DURATION: int = int(os.getenv("HLS_SEGMENT_DURATION", "10"))
    HLS_PLAYLIST_TYPE: str = os.getenv("HLS_PLAYLIST_TYPE", "vod")
    HLS_KEY_URL: str = os.getenv("HLS_KEY_URL", "https://api.example.com/api/v1/playback/{video_id}/key")

    # 帧指纹
    FINGERPRINT_KEYFRAME_INTERVAL: int = int(os.getenv("FINGERPRINT_KEYFRAME_INTERVAL", "30"))  # 秒
    FINGERPRINT_HASH_SIZE: int = int(os.getenv("FINGERPRINT_HASH_SIZE", "64"))  # bits

    # 水印
    WATERMARK_STRENGTH: float = float(os.getenv("WATERMARK_STRENGTH", "0.1"))

    # 监控
    ENABLE_METRICS: bool = os.getenv("ENABLE_METRICS", "true").lower() == "true"
    METRICS_PORT: int = int(os.getenv("METRICS_PORT", "9100"))


settings = Settings()