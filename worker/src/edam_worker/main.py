"""
EDAM Worker 入口
启动：python -m edam_worker.main
"""
import asyncio
import json
import logging
import os
import signal
import sys
from contextlib import asynccontextmanager
from typing import Any, Dict

import structlog
from aio_pika import Message, connect_robust, ExchangeType
from aio_pika.abc import AbstractIncomingMessage
from fastapi import FastAPI
from prometheus_client import Counter, Histogram, start_http_server

from .config import settings
from .processors.video import VideoProcessor
from .processors.document import DocumentProcessor
from .processors.watermark import WatermarkProcessor
from .ops import router as ops_router, register_publisher

# 结构化日志
structlog.configure(
    processors=[
        structlog.contextvars.merge_contextvars,
        structlog.processors.add_log_level,
        structlog.processors.TimeStamper(fmt="iso"),
        structlog.processors.JSONRenderer(),
    ],
    wrapper_class=structlog.make_filtering_bound_logger(logging.INFO),
    logger_factory=structlog.PrintLoggerFactory(),
    cache_logger_on_first_use=True,
)
log = structlog.get_logger()

# Prometheus 指标
TASKS_PROCESSED = Counter(
    "worker_tasks_processed_total",
    "Total tasks processed",
    ["task_type", "result"],
)
TASK_DURATION = Histogram(
    "worker_task_duration_seconds",
    "Task processing duration",
    ["task_type"],
)


class WorkerApp:
    """Worker 应用主类"""

    def __init__(self) -> None:
        self.video_processor = VideoProcessor()
        self.document_processor = DocumentProcessor()
        self.watermark_processor = WatermarkProcessor()
        self.connection = None
        self.channel = None

    async def start(self) -> None:
        """启动 Worker"""
        log.info("worker_starting", rabbitmq_host=settings.RABBITMQ_HOST)

        # 启动 Prometheus metrics server
        if settings.ENABLE_METRICS:
            start_http_server(settings.METRICS_PORT)
            log.info("metrics_server_started", port=settings.METRICS_PORT)

        # 连接 RabbitMQ
        self.connection = await connect_robust(
            host=settings.RABBITMQ_HOST,
            port=settings.RABBITMQ_PORT,
            login=settings.RABBITMQ_USER,
            password=settings.RABBITMQ_PASSWORD,
            virtualhost=settings.RABBITMQ_VHOST,
        )
        self.channel = await self.connection.channel()
        await self.channel.set_qos(prefetch_count=10)

        # 声明交换机
        exchange = await self.channel.declare_exchange(
            "edam.tasks", ExchangeType.DIRECT, durable=True,
        )

        # 声明 + 绑定队列
        queues_config = [
            ("q.video.preprocess", "video.preprocess", self.handle_video),
            ("q.document.preprocess", "document.preprocess", self.handle_document),
            ("q.watermark", "watermark", self.handle_watermark),
        ]
        for queue_name, routing_key, handler in queues_config:
            queue = await self.channel.declare_queue(queue_name, durable=True)
            await queue.bind(exchange, routing_key=routing_key)
            await queue.consume(handler)
            log.info("queue_bound", queue=queue_name, routing_key=routing_key)

        log.info("worker_started")

    async def stop(self) -> None:
        """优雅关闭"""
        log.info("worker_stopping")
        if self.connection:
            await self.connection.close()
        log.info("worker_stopped")

    async def publish_video_preprocess(self, payload: Dict[str, Any]) -> None:
        """把任务投递到 q.video.preprocess（运维接口用）。

        复用 startup 时已经建立的连接（channel 单独开，避免污染消费侧）。

        重复声明交换机（passive=True）— 与已有 RabbitMQ 拓扑保持一致，
        不引入新 exchange。
        """
        if self.connection is None or self.connection.is_closed:
            raise RuntimeError("rabbitmq connection not available")
        channel = await self.connection.channel()
        try:
            exchange = await channel.declare_exchange(
                "edam.tasks", ExchangeType.DIRECT, durable=True, passive=True,
            )
            message_body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            await exchange.publish(
                Message(
                    body=message_body,
                    content_type="application/json",
                    delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
                ),
                routing_key="video.preprocess",
            )
            log.info("ops_published", payload=payload)
        finally:
            await channel.close()

    async def handle_video(self, message: AbstractIncomingMessage) -> None:
        """处理视频预处理任务"""
        async with message.process(requeue=False):
            try:
                import json
                payload = json.loads(message.body)
                with TASK_DURATION.labels(task_type="video").time():
                    await self.video_processor.process(payload)
                TASKS_PROCESSED.labels(task_type="video", result="success").inc()
            except Exception as e:
                log.error("video_task_failed", error=str(e))
                TASKS_PROCESSED.labels(task_type="video", result="failed").inc()
                raise

    async def handle_document(self, message: AbstractIncomingMessage) -> None:
        """处理文档预处理任务"""
        async with message.process(requeue=False):
            try:
                import json
                payload = json.loads(message.body)
                with TASK_DURATION.labels(task_type="document").time():
                    await self.document_processor.process(payload)
                TASKS_PROCESSED.labels(task_type="document", result="success").inc()
            except Exception as e:
                log.error("document_task_failed", error=str(e))
                TASKS_PROCESSED.labels(task_type="document", result="failed").inc()
                raise

    async def handle_watermark(self, message: AbstractIncomingMessage) -> None:
        """处理水印/帧指纹任务"""
        async with message.process(requeue=False):
            try:
                import json
                payload = json.loads(message.body)
                with TASK_DURATION.labels(task_type="watermark").time():
                    await self.watermark_processor.process(payload)
                TASKS_PROCESSED.labels(task_type="watermark", result="success").inc()
            except Exception as e:
                log.error("watermark_task_failed", error=str(e))
                TASKS_PROCESSED.labels(task_type="watermark", result="failed").inc()
                raise


worker = WorkerApp()


@asynccontextmanager
async def lifespan(app: FastAPI):
    await worker.start()
    # lifespan 启动后注入 publisher —— 比 worker.start() 抛错的话不发冷消息
    register_publisher(worker.publish_video_preprocess)
    log.info("ops_publisher_registered")
    yield
    await worker.stop()


app = FastAPI(
    title="EDAM Worker",
    version="3.1.0",
    lifespan=lifespan,
)


@app.get("/health/live")
async def health_live():
    return {"status": "alive"}


@app.get("/health/ready")
async def health_ready():
    return {"status": "ok"}


# 运维接口（受 OPS_ENABLED / OPS_TOKEN 控制；fail-closed）
app.include_router(ops_router)


def handle_signal(signum, frame):
    """处理 SIGTERM / SIGINT 优雅关闭"""
    log.info("signal_received", signum=signum)
    asyncio.create_task(worker.stop())
    sys.exit(0)


signal.signal(signal.SIGTERM, handle_signal)
signal.signal(signal.SIGINT, handle_signal)


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "edam_worker.main:app",
        host="0.0.0.0",
        port=settings.HTTP_PORT,
        log_level="info",
    )