"""
EDAM Worker 入口
启动：python -m edam_worker.main
"""
import asyncio
import datetime
import json
import logging
import os
import signal
import sys
from contextlib import asynccontextmanager
from typing import Any, Awaitable, Callable, Dict, Optional

import structlog
from aio_pika import DeliveryMode, Message, connect_robust, ExchangeType
from aio_pika.abc import AbstractIncomingMessage
from fastapi import FastAPI
from minio import Minio
from prometheus_client import Counter, Histogram, start_http_server
import requests

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
    "Total tasks processed (final outcome only, after retries)",
    ["task_type", "result"],   # result ∈ {success, failed_permanent}
)
TASK_ATTEMPTS = Counter(
    "worker_task_attempts_total",
    "Total individual task processing attempts (counts retries)",
    ["task_type", "attempt_result"],   # attempt_result ∈ {success, failed}
)
TASK_DURATION = Histogram(
    "worker_task_duration_seconds",
    "Task processing duration (single attempt; not summed over retries)",
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
        # 最近一次任务结果（task_type → {last_success_*, last_failure_*}）
        # 暴露给 /health/worker，让运维一眼看清"worker 真活着 + 真在干活"
        self._last_task_state: Dict[str, Dict[str, Any]] = {}

    # ----------------------------------------------------------------
    # 任务状态追踪 + 重试包装
    # ----------------------------------------------------------------

    def _record_task(
        self,
        task_type: str,
        success: bool,
        video_id: Optional[int] = None,
        error: Optional[BaseException] = None,
    ) -> None:
        state = self._last_task_state.setdefault(task_type, {})
        now_iso = datetime.datetime.now().isoformat(timespec="seconds")
        if success:
            state["last_success_at"] = now_iso
            if video_id is not None:
                state["last_success_video_id"] = video_id
        else:
            state["last_failure_at"] = now_iso
            if video_id is not None:
                state["last_failure_video_id"] = video_id
            if error is not None:
                state["last_failure_error_type"] = type(error).__name__
                state["last_failure_error_message"] = str(error)[:500]

    async def _process_with_retry(
        self,
        task_type: str,
        payload: Dict[str, Any],
        processor_fn: Callable[[Dict[str, Any]], Awaitable[None]],
    ) -> None:
        """带指数退避的内部重试包装。

        与 RabbitMQ requeue 无关 —— 即便全部失败，仍由外层
        ``async with message.process(requeue=False)`` 把消息丢掉，
        不会在队列里"假活"占位。

        任何一次重试用尽都把 ``payload`` 与最终错误打到日志里
        （loud error），并更新 ``_last_task_state`` 供 /health/worker 暴露。
        """
        max_attempts = max(1, settings.WORKER_MAX_ATTEMPTS)
        base = max(0.0, settings.WORKER_RETRY_BASE_BACKOFF_SEC)
        video_id = payload.get("video_id") if isinstance(payload, dict) else None
        last_error: Optional[BaseException] = None

        for attempt in range(1, max_attempts + 1):
            try:
                with TASK_DURATION.labels(task_type=task_type).time():
                    await processor_fn(payload)
                # 成功
                TASK_ATTEMPTS.labels(task_type=task_type, attempt_result="success").inc()
                self._record_task(task_type, success=True, video_id=video_id)
                TASKS_PROCESSED.labels(task_type=task_type, result="success").inc()
                if attempt > 1:
                    log.info(
                        f"{task_type}_task_succeeded_after_retry",
                        video_id=video_id,
                        attempt=attempt,
                    )
                return
            except asyncio.CancelledError:
                # 收到 SIGTERM / uvicorn 优雅关闭时的 cancel：
                # 绝不能当成"任务失败"去重试，否则关闭流程被反复拖延，
                # 且会在 MinIO 上留下半成品目录。直接向上抛。
                raise
            except Exception as e:
                last_error = e
                TASK_ATTEMPTS.labels(task_type=task_type, attempt_result="failed").inc()
                if attempt < max_attempts:
                    backoff = base * (2 ** (attempt - 1))
                    log.warning(
                        f"{task_type}_task_attempt_failed",
                        video_id=video_id,
                        attempt=attempt,
                        max_attempts=max_attempts,
                        backoff_sec=backoff,
                        error_type=type(e).__name__,
                        error=str(e)[:300],
                    )
                    if backoff > 0:
                        await asyncio.sleep(backoff)
                else:
                    # 用尽
                    self._record_task(task_type, success=False, video_id=video_id, error=e)
                    TASKS_PROCESSED.labels(
                        task_type=task_type, result="failed_permanent"
                    ).inc()
                    log.error(
                        f"{task_type}_task_failed_permanent",
                        video_id=video_id,
                        max_attempts=max_attempts,
                        final_error_type=type(e).__name__,
                        final_error=str(e)[:500],
                    )

        # 跑完所有 attempt 仍未成功 → 把最后一次异常 raise 出
        # 给外层 async with message.process(requeue=False) 当作 NACK 处理
        if last_error is not None:
            raise last_error
        raise RuntimeError(
            f"{task_type} task exited without success but no exception captured"
        )

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
                    delivery_mode=DeliveryMode.PERSISTENT,
                ),
                routing_key="video.preprocess",
            )
            log.info("ops_published", payload=payload)
        finally:
            await channel.close()

    async def handle_video(self, message: AbstractIncomingMessage) -> None:
        """处理视频预处理任务（含内部重试）"""
        async with message.process(requeue=False):
            try:
                payload = json.loads(message.body)
            except Exception as e:
                # payload 不合法不值得重试 —— ACK 掉（不 requeue），尽早暴露
                log.error("video_task_invalid_payload", error=str(e)[:200],
                          body_preview=message.body[:200].decode(errors="replace"))
                return
            await self._process_with_retry(
                "video", payload, self.video_processor.process
            )

    async def handle_document(self, message: AbstractIncomingMessage) -> None:
        """处理文档预处理任务（含内部重试）"""
        async with message.process(requeue=False):
            try:
                payload = json.loads(message.body)
            except Exception as e:
                log.error("document_task_invalid_payload", error=str(e)[:200],
                          body_preview=message.body[:200].decode(errors="replace"))
                return
            await self._process_with_retry(
                "document", payload, self.document_processor.process
            )

    async def handle_watermark(self, message: AbstractIncomingMessage) -> None:
        """处理水印/帧指纹任务（含内部重试）"""
        async with message.process(requeue=False):
            try:
                payload = json.loads(message.body)
            except Exception as e:
                log.error("watermark_task_invalid_payload", error=str(e)[:200],
                          body_preview=message.body[:200].decode(errors="replace"))
                return
            await self._process_with_retry(
                "watermark", payload, self.watermark_processor.process
            )


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


# ----------------------------------------------------------------
# /health/worker：worker 自检（RabbitMQ / MinIO / Backend + 最近一次任务）
# ----------------------------------------------------------------

def _make_minio_client() -> Minio:
    """与 VideoProcessor 一致配置的 MinIO 客户端（仅用于健康探测）"""
    endpoint = settings.MINIO_ENDPOINT.replace("http://", "").replace("https://", "")
    return Minio(
        endpoint,
        access_key=settings.MINIO_ACCESS_KEY,
        secret_key=settings.MINIO_SECRET_KEY,
        secure=settings.MINIO_ENDPOINT.startswith("https://"),
    )


def _probe_minio() -> Dict[str, Any]:
    """同步探测 MinIO：bucket 是否可达。一律 3s 兜底。"""
    try:
        client = _make_minio_client()
        bucket = settings.MINIO_BUCKET_VIDEOS
        exists = client.bucket_exists(bucket)
        return {
            "ok": bool(exists),
            "endpoint": settings.MINIO_ENDPOINT,
            "bucket_videos": bucket,
        }
    except Exception as e:
        return {
            "ok": False,
            "endpoint": settings.MINIO_ENDPOINT,
            "error": f"{type(e).__name__}: {e}",
        }


def _probe_backend() -> Dict[str, Any]:
    """同步探测后端 Spring Boot /actuator/health/liveness。一律 3s 兜底。"""
    # BACKEND_BASE_URL 可能已经带了 context-path（/api/v1），
    # 也可能没带 —— 两种都要能拼对，否则探测永远 404
    base = settings.BACKEND_BASE_URL.rstrip("/")
    candidates = [
        f"{base}/actuator/health/liveness",
        f"{base}/api/v1/actuator/health/liveness",
    ]
    last_error = None
    for url in candidates:
        try:
            resp = requests.get(url, timeout=3)
            if resp.status_code < 500:
                return {
                    "ok": True,
                    "status": resp.status_code,
                    "url": url,
                }
            last_error = f"HTTP {resp.status_code}"
        except Exception as e:
            last_error = f"{type(e).__name__}: {e}"
    return {
        "ok": False,
        "url": candidates[0],
        "error": last_error,
    }


@app.get("/health/worker")
async def health_worker():
    """聚合 worker 三类依赖 + 最近一次任务成败。

    返回总是 200，由 body 里的 ``overall_ok`` 表达整体可用性；
    便于 curl / Prometheus blackbox / 自研脚本同时用，K8s liveness probe
    自己再决定要不要单独接。
    """
    checks: Dict[str, Any] = {}

    # 1. RabbitMQ
    conn = worker.connection
    checks["rabbitmq"] = {
        "ok": conn is not None and not conn.is_closed,
        "host": settings.RABBITMQ_HOST,
        "port": settings.RABBITMQ_PORT,
    }
    if not checks["rabbitmq"]["ok"]:
        checks["rabbitmq"]["error"] = "connection not established or closed"

    # 2. MinIO（同步，bypass 异步循环）
    checks["minio"] = await asyncio.to_thread(_probe_minio)

    # 3. 后端
    checks["backend"] = await asyncio.to_thread(_probe_backend)

    # 4. 最近一次任务状态（_last_task_state 来自 _record_task）
    # 拷贝出去防外部意外改 worker 实例
    checks["task_state"] = json.loads(json.dumps(worker._last_task_state))

    overall_ok = all(
        c.get("ok") for c in (checks["rabbitmq"], checks["minio"], checks["backend"])
    )
    return {
        "overall_ok": overall_ok,
        "ts": datetime.datetime.now().isoformat(timespec="seconds"),
        **checks,
    }


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