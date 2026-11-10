"""
EDAM Worker 入口
启动：python -m edam_worker.main
"""
import asyncio
import datetime
import json
import logging
import time
from contextlib import asynccontextmanager
from typing import Any, Awaitable, Callable, Dict, Optional

import structlog
from aio_pika import DeliveryMode, Message, connect_robust, ExchangeType
from aio_pika.abc import AbstractIncomingMessage

try:
    from aio_pika.exceptions import ChannelInvalidStateError
except ImportError:  # pragma: no cover - 老版本 aio_pika 没转出这个异常
    # 异常类实际定义在 aiormq 里（aio_pika 从那里转出），
    # 所以退回直接引用 aiormq —— 它是 aio_pika 的硬依赖，一定在
    from aiormq.exceptions import ChannelInvalidStateError
from fastapi import FastAPI
from minio import Minio
from prometheus_client import start_http_server
import requests

from . import __version__
from .config import settings, timeout_budget_warnings
from .errors import PermanentWorkerError, TaskDeadlineExceeded
from .ffmpeg_probe import probe_ffmpeg, remediation
from .hls_keyinfo import probe_key_info_layout
from .fonts import find_font
from .metrics import TASK_ATTEMPTS, TASK_DURATION, TASKS_PROCESSED
from .processors.video import VideoProcessor
from .processors.document import DocumentProcessor
from .ops import router as ops_router, register_publisher
from .internal import router as internal_router

# 结构化日志
structlog.configure(
    processors=[
        structlog.contextvars.merge_contextvars,
        structlog.processors.add_log_level,
        structlog.processors.TimeStamper(fmt="iso"),
        structlog.processors.JSONRenderer(),
    ],
    # 日志级别可配（此前 LOG_LEVEL 配置项完全没被使用）
    wrapper_class=structlog.make_filtering_bound_logger(
        getattr(logging, settings.LOG_LEVEL.upper(), logging.INFO)),
    logger_factory=structlog.PrintLoggerFactory(),
    cache_logger_on_first_use=True,
)
log = structlog.get_logger()

class WorkerApp:
    """Worker 应用主类"""

    def __init__(self) -> None:
        self.video_processor = VideoProcessor()
        self.document_processor = DocumentProcessor()
        self.connection = None
        self.channel = None
        # 最近一次任务结果（task_type → {last_success_*, last_failure_*}）
        # 暴露给 /health/worker，让运维一眼看清"worker 真活着 + 真在干活"
        self._last_task_state: Dict[str, Dict[str, Any]] = {}
        # 优雅关闭进行中 —— 用来区分"我们自己在关"与"broker 掐了我们"
        # 两种 CancelledError（见 _process_with_retry）
        self._shutting_down = False

    # ----------------------------------------------------------------
    # 任务状态追踪 + 重试包装
    # ----------------------------------------------------------------

    async def _run_with_deadline(
        self,
        task_type: str,
        processor_fn: Callable[[Dict[str, Any]], Awaitable[None]],
        payload: Dict[str, Any],
        remaining: Optional[float],
    ) -> None:
        """在剩余的墙钟预算内跑完一次任务尝试。

        **为什么 worker 要自己掐表**：RabbitMQ 的未确认投递超时
        （consumer_timeout，默认 30 分钟）一旦先到，broker 会**掐断 channel**，
        正在跑的 consumer 协程被连带取消 ⇒ 异常以 CancelledError 的形式冒出来
        （Python 3.8+ 里它继承 BaseException，`except Exception` 抓不到）。

        那种失败在监控上是**完全隐形**的：没有失败日志、
        Prometheus 计数器不动、/health/worker 也不更新 ——
        而视频确实没转出来，用户点播放还是 404。

        worker 自己先超时，就拿到一个**干净、可诊断、可告警**的失败：
        TaskDeadlineExceeded（永久失败，不重试 —— 同样的输入再跑一遍
        还是超，每次重试都是几小时的 CPU）。

        超时用 ``asyncio.wait`` 实现：它按"跑完了没有"判定，
        不会把内部抛出的 TimeoutError（socket 超时也是它）误判成超时；
        超时后取消内层协程，处理器里已经处理好了 CancelledError
        （记失败、通知后端、杀掉 FFmpeg 子进程），所以取消语义不会丢。

        :param remaining: 剩余预算（秒）。None = 不限。
            **必须是"整条消息"的剩余预算**，不能是单次尝试的 ——
            broker 的未确认投递超时覆盖的是**整条消息的处理时长**
            （含全部重试与退避），不是单次尝试。
        """
        if remaining is None:
            await processor_fn(payload)
            return

        # 用 ``asyncio.wait`` 而不是 ``asyncio.wait_for`` ——
        # 后者靠异常类型区分"超时"和"内部抛的 TimeoutError"，
        # 而 Python 3.11+ 里 ``asyncio.TimeoutError`` 就是内置的
        # ``TimeoutError``，**socket 超时（socket.timeout）也是它**
        # ⇒ MinIO 的一次网络抖动会被误报成"任务超时"。
        # asyncio.wait 用"跑完了没有"来判定，没有这种歧义。
        inner = asyncio.ensure_future(processor_fn(payload))
        try:
            done, pending = await asyncio.wait({inner}, timeout=remaining)
        except asyncio.CancelledError:
            # 外层被取消（broker 掐断 channel / 优雅关闭）
            # ⇒ 内层必须一起取消，否则它会变成没人管的孤儿任务，
            #    FFmpeg 还在跑、回调还在发。
            inner.cancel()
            await asyncio.gather(inner, return_exceptions=True)
            raise

        if pending:
            # 真的超时了 —— 取消内层（它的 finally 会把失败状态通知后端），
            # 等它收尾，再抛一个明确的错误。
            inner.cancel()
            await asyncio.gather(inner, return_exceptions=True)
            log.error(
                f"{task_type}_task_deadline_exceeded",
                deadline_sec=settings.WORKER_TASK_DEADLINE_SEC,
                hint=(
                    f"任务总时长超过 {settings.WORKER_TASK_DEADLINE_SEC:.0f}s。"
                    "这个视频本身太大（转码要更久）—— "
                    "调大 WORKER_TASK_DEADLINE_SEC 可以放宽，"
                    "但必须同时确认 broker 的 consumer_timeout 比它更大。"
                ),
            )
            raise TaskDeadlineExceeded(
                f"任务总时长超过上限（{settings.WORKER_TASK_DEADLINE_SEC:.0f}s）"
            )

        # 跑完了 —— 把内层的异常原样抛出去（不吞、不改写）
        inner.result()

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

        **任务总时长有上限**（WORKER_TASK_DEADLINE_SEC）：
        整个流水线（下载 + 转码 + 指纹 + 上传）必须在上限内跑完。
        原因见 config.py 里那三个超时的排序说明 ——
        broker 的未确认投递超时一旦先到，channel 会被掐断，
        正在跑的任务被连带取消（CancelledError），
        而 CancelledError 继承的是 BaseException ⇒ 这次失败**完全看不见**。
        worker 自己先超时，才拿得到一个干净、可诊断、可告警的失败。

        ⚠️ 这个预算是**整条消息**的（所有重试加起来），
        不是每次尝试各算一份 —— broker 的未确认投递超时覆盖的是
        消息从投递到 ack 的**全部**时长。3 次重试各 1 小时 = 3 小时，
        而 broker 只给了 30 分钟 ⇒ 第二次尝试照样被掐断。
        """
        max_attempts = max(1, settings.WORKER_MAX_ATTEMPTS)
        base = max(0.0, settings.WORKER_RETRY_BASE_BACKOFF_SEC)
        video_id = payload.get("video_id") if isinstance(payload, dict) else None
        last_error: Optional[BaseException] = None

        # 整条消息的墙钟预算 —— 所有尝试共享，见 docstring 的说明
        budget = settings.WORKER_TASK_DEADLINE_SEC
        deadline_at = (
            time.monotonic() + budget if budget > 0 else None
        )

        for attempt in range(1, max_attempts + 1):
            remaining = (
                deadline_at - time.monotonic() if deadline_at is not None else None
            )
            if remaining is not None and remaining <= 0:
                # 预算已经用光 —— 再跑一次也只是超时，直接按超时失败收场
                last_error = TaskDeadlineExceeded(
                    f"任务总时长超过上限（{budget:.0f}s）"
                )
                self._record_task(task_type, success=False, video_id=video_id,
                                  error=last_error)
                TASKS_PROCESSED.labels(
                    task_type=task_type, result="failed_permanent"
                ).inc()
                log.error(f"{task_type}_task_deadline_exceeded",
                          deadline_sec=budget, attempt=attempt)
                raise last_error

            try:
                with TASK_DURATION.labels(task_type=task_type).time():
                    await self._run_with_deadline(
                        task_type, processor_fn, payload, remaining
                    )
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
                # 协程被取消 —— 两种来源，处理方式**完全不同**：
                #
                # ① worker 自己优雅关闭（SIGTERM）—— 不是任务失败。
                #    消息未 ack ⇒ broker 会自动重投给别人。
                #    绝不能当成失败去重试，否则关闭流程被反复拖延。
                #
                # ② broker 掐断了 channel（未确认投递超时到点）——
                #    **这是任务失败**，而且必须被记录：
                #    原来的代码只是 `raise`，于是
                #    - 不进 Prometheus（失败率永远是 0）
                #    - 不更新 /health/worker 的 last_failure_*
                #    - 没有一条日志能说明"这个视频为什么没转出来"
                #    ⇒ 一次被 broker 掐死的转码，在监控上完全隐形
                #    （2026-11-10 事故）。
                if self._shutting_down:
                    log.info(f"{task_type}_task_cancelled_during_shutdown",
                             video_id=video_id)
                else:
                    self._record_task(task_type, success=False, video_id=video_id,
                                      error=asyncio.CancelledError("channel closed by broker"))
                    TASKS_PROCESSED.labels(
                        task_type=task_type, result="cancelled"
                    ).inc()
                    log.error(
                        f"{task_type}_task_cancelled",
                        video_id=video_id,
                        hint=(
                            "broker 掐断了消费 channel —— 通常是未确认投递超时"
                            "（consumer_timeout，默认 30 分钟）到点。"
                            "消息未确认，broker 会自动重投。"
                            "对策见 ops/rabbitmq/README.md。"
                        ),
                    )
                raise
            except PermanentWorkerError as e:
                # 重试也不会变好（配置/鉴权问题，最典型的是回调被 401 拒了）。
                # 不重试整个任务 —— 重转码 3 遍结果完全一样，只是白白烧 CPU。
                # 但**必须**记成失败：回调失败意味着后端不知道这次转码的结果，
                # hls_status 会永远停在 pending，用户点播放还是 404。
                # 记成 success 的话 Prometheus 会显示 100% 成功率，
                # 而实际上后端什么都没收到 —— 这就是"静默失败"。
                self._record_task(task_type, success=False, video_id=video_id, error=e)
                TASKS_PROCESSED.labels(
                    task_type=task_type, result="failed_permanent"
                ).inc()
                log.error(
                    f"{task_type}_task_failed_permanent",
                    video_id=video_id,
                    error_type=type(e).__name__,
                    error=str(e)[:500],
                )
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

        # 启动就说清楚 token 配没配 —— 不然这个坑要等到"视频播不出来"
        # 的时候才被发现（2026-10-29 就是这样：回调被 401 拒了 3 次，
        # 而日志里只有一行 unauthorized，谁也不知道该改哪一边）。
        if not settings.WORKER_TOKEN:
            log.warning(
                "worker_token_not_configured",
                hint=("后端 PATCH /videos/{id}/status 要求 X-Worker-Token（fail-closed）。"
                      "不配的话回调一律 401 —— 转码完成了 hls_status 也不会变，"
                      "用户点播放还是 404。必须与后端的 edam.worker.token 完全一致"),
            )

        # 超时预算自检 —— 三档超时必须自上而下递减。
        # 不自洽 ⇒ broker 会先掐断 channel ⇒ 转码被取消 ⇒ 而 CancelledError
        # 在监控上是隐形的（见 config.py 的说明）。
        # 这个坑不能等到"某个大视频转了 30 分钟然后消失"才发现 ——
        # 启动时就说清楚。
        budget_warnings = timeout_budget_warnings()
        if budget_warnings:
            for w in budget_warnings:
                log.error("worker_timeout_budget_invalid", detail=w)
        else:
            log.info(
                "worker_timeout_budget_ok",
                broker_consumer_timeout_sec=settings.RABBITMQ_CONSUMER_TIMEOUT_SEC,
                task_deadline_sec=settings.WORKER_TASK_DEADLINE_SEC,
                ffmpeg_timeout_sec=settings.FFMPEG_TIMEOUT_SEC,
            )

        # FFmpeg 能力自检 —— 把"第一个视频转码失败"提前到启动时暴露。
        # 不做这个自检的话，环境缺东西只会在第一个视频到来时才炸，
        # 而且抛出的异常只有一句"转码失败"，看不出缺的是编码器还是滤镜。
        await self._probe_ffmpeg()

        # key.info 格式探测 —— 各版本 FFmpeg 对第二行的处理不一致
        # （是"key 文件路径"还是"key 的十六进制"）。
        # 2026-10-29 线上每条视频都转码失败，正是这个格式搞错了。
        # 探测一次（~1 秒合成视频），后续每条视频都直接用对的写法，
        # 不用再"先失败一次再换一种"。
        await self._probe_key_info_layout()

    async def _probe_key_info_layout(self) -> None:
        """探测本机 FFmpeg 认哪种 key.info 写法，结果记进日志。"""
        try:
            probe = await asyncio.to_thread(
                probe_key_info_layout, settings.FFMPEG_PATH
            )
        except Exception as e:
            log.error("hls_key_info_probe_failed",
                      error_type=type(e).__name__, error=str(e)[:300])
            return

        layout = probe.get("layout")
        if layout:
            # 记住它 —— 转码时直接用，不用再逐个试
            self.video_processor.set_key_info_layout(layout)
            log.info("hls_key_info_layout_detected",
                     layout=layout,
                     hint=("key.info 第一行=key 的 URI，第二行=key 文件的路径"
                           "（不是 key 本身）"))
        else:
            # 探测不出来 ≠ worker 该死 —— 转码时仍会逐个试两种写法。
            # 但必须喊出来，否则又是一条"转码失败但没人知道为什么"。
            log.warning("hls_key_info_layout_unknown",
                        tried=probe.get("tried"),
                        error=probe.get("error"),
                        hint=("两种 key.info 写法都没成功 —— 转码会逐个试。"
                              "看 tried 里的 output_tail 定位原因"))

    async def _probe_ffmpeg(self) -> None:
        """检查 FFmpeg 是否具备转码所需的能力（编码器 / 滤镜 / 字体）。"""
        try:
            probe = await asyncio.to_thread(
                probe_ffmpeg, settings.FFMPEG_PATH, find_font()
            )
        except Exception as e:
            log.error("ffmpeg_capabilities_check_failed",
                      error_type=type(e).__name__, error=str(e)[:300])
            return

        if probe.get("ok"):
            log.info("ffmpeg_capabilities_ok",
                     version=probe.get("version"),
                     font_path=probe.get("font_path"))
        else:
            # 缺能力 ≠ worker 该死 —— 文档/水印链路照样能干活，
            # 只是视频转不了。所以只报警告，不退出。
            log.warning(
                "ffmpeg_capabilities_missing",
                version=probe.get("version"),
                missing_encoders=probe.get("missing_encoders"),
                missing_filters=probe.get("missing_filters"),
                font_path=probe.get("font_path"),
                remediation=remediation(probe),
            )

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
        #
        # v3.4.1 起只有两条：原先还有 q.watermark，但它**从来没有生产者** ——
        # 后端从不往 edam.tasks 的 "watermark" 路由发消息。
        # 真实的盲水印嵌入走的是 q.document.preprocess
        # （DocumentProcessor._watermark → WatermarkProcessor.embed_*），
        # q.watermark 是一条平行的、无人使用的支线：留着它，运维看队列监控
        # 会以为这条链路是活的。
        queues_config = [
            ("q.video.preprocess", "video.preprocess", self.handle_video),
            ("q.document.preprocess", "document.preprocess", self.handle_document),
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
        # 先置位：**关连接会取消正在跑的 consumer 协程**，
        # 那些 CancelledError 是"我们自己关的"，不是失败，不该记成失败
        self._shutting_down = True
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
        try:
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
        except ChannelInvalidStateError as e:
            # channel 已经没了（broker 掐断 / 连接断开）⇒ ACK/reject 送不出去。
            #
            # **不能让它顶替掉真正的失败原因**：异常发生在
            # ``message.process()`` 的 __aexit__ 里，而真正的失败
            # （通常是 CancelledError —— broker 的未确认投递超时到点）
            # 挂在 __context__ 上。直接抛 ChannelInvalidStateError 的话，
            # 日志里就只剩一句没头没尾的 "channel invalid state"，
            # 而"是谁掐了我们"这条关键线索被吞掉了。
            cause = e.__context__
            if cause is not None:
                raise cause from e
            raise

    async def handle_document(self, message: AbstractIncomingMessage) -> None:
        """处理文档预处理任务（含内部重试）"""
        try:
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
        except ChannelInvalidStateError as e:
            # 同 handle_video：channel 已死，别让善后失败顶替掉真正的失败原因
            cause = e.__context__
            if cause is not None:
                raise cause from e
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
    # 版本号跟着包版本走 —— 原先这里写死 "3.4.1"，发版时漏改
    version=__version__,
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

# 内网接口（Backend → Worker；受 WORKER_TOKEN 控制；fail-closed）
app.include_router(internal_router)

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

# ----------------------------------------------------------------
# 关闭信号
#
# **这里刻意不注册自己的 SIGTERM / SIGINT handler。**
#
# uvicorn 自己会接管这两个信号（capture_signals）：收到 SIGTERM 后
# 停止接受新连接 → 跑 lifespan 的关闭段 → 我们在 lifespan 里
# ``await worker.stop()`` 关掉 RabbitMQ 连接 —— 这就是完整的优雅关闭。
#
# 自己再注册一遍反而有害：
#   1. 会**覆盖** uvicorn 的 handler，lifespan 的关闭段没机会执行
#      ⇒ RabbitMQ 连接不关、正在转码的任务被硬切 ⇒ MinIO 留半成品目录；
#   2. 在信号处理器里 ``sys.exit(0)`` 会在主线程抛 SystemExit，
#      直接把事件循环掀掉，连"等当前任务收尾"都不会发生。
#
# 历史教训：这个 handler 曾经因为一次"死代码清理"被删掉了 import 却没删调用，
# worker 一启动就 NameError 崩掉 —— 2026-10-23 ~ 2026-10-29 期间
# worker 从来没能真正起来过。见 modify/2026-10-29-fix-worker-startup-crash.md
# ----------------------------------------------------------------

if __name__ == "__main__":
    import uvicorn
    # 传 **app 对象**而不是 "edam_worker.main:app" 字符串：
    # 字符串形式会让 uvicorn 再 import 一遍 edam_worker.main，
    # 而 `python -m edam_worker.main` 走 runpy，模块体**已经**以
    # __main__ 的身份执行过一遍 —— 于是模块体被执行两次。
    # 指标注册发生在模块导入期（见 metrics.py），两次执行 ⇒
    # DuplicateTimeseries ⇒ 启动即崩。
    uvicorn.run(
        app,
        host="0.0.0.0",
        port=settings.HTTP_PORT,
        log_level="info",
    )