"""
EDAM Worker 运维接口

提供按 video_id 重新触发 HLS 转码 / 探测 MinIO 上 HLS 文件等
运维操作，受 token 鉴权保护。

启用方式（环境变量）：
    OPS_ENABLED=true
    OPS_TOKEN=<强随机 token，base64 / hex 均可>

失败模式：
- OPS_ENABLED != true          → 端点直接 404（对外完全隐身）
- OPS_TOKEN 为空              → 即便 OPS_ENABLED=true 也 403（fail-closed）
- token 不匹配                 → 403

约定：
- 一切"重新触发"都走原有的 RabbitMQ 队列 q.video.preprocess，复用
  已有的 VideoProcessor.process() 路径，避免在 worker 里另起一套
  状态机
- 不直接调用后端查 input_path —— 后端 GET /videos/{id} 需 JWT，
  worker 端没有用户 token。要求调用方把 input_path 显式带过来
  （运维场景下已知，列表一下 MinIO 或查后端 DB 都很容易）
"""

import json
import secrets
from typing import Any, Callable, Dict, List, Optional

import aio_pika
from aio_pika import ExchangeType, Message
from fastapi import APIRouter, Header, HTTPException, status
from minio import Minio
from minio.error import S3Error
from pydantic import BaseModel, Field, field_validator

from .config import settings

router = APIRouter(prefix="/ops", tags=["ops"])

# 由 main.py 在 lifespan 启动时注入，避免循环 import
_publisher: Optional[Callable[[Dict[str, Any]], Any]] = None


def register_publisher(fn: Callable[[Dict[str, Any]], Any]) -> None:
    """main.py 在 lifespan 启动后调用本函数注入 VideoProcessor 的发布器。

    不直接传 worker 实例是为了避免 ops.py 反向依赖 main.py 的模块结构。
    """
    global _publisher
    _publisher = fn


# ----------------------------------------------------------------
# Pydantic 模型
# ----------------------------------------------------------------

class ReprocessRequest(BaseModel):
    """POST /ops/videos/{video_id}/reprocess-hls 请求体"""

    input_path: str = Field(
        ...,
        min_length=1,
        max_length=512,
        description="原始视频在 MinIO 里的对象名（与 upload 阶段一致，例如 `uploads/3/abc.mp4`）",
    )
    classification_lv: Optional[str] = Field(
        default=None,
        max_length=16,
        description="透传给 worker；留空时默认 'L2'",
    )
    employee_no: Optional[str] = Field(
        default=None,
        max_length=64,
        description="水印用员工号；留空时用占位符（不影响 HLS 重新切片）",
    )
    dry_run: bool = Field(
        default=False,
        description="true 时只校验 + 探活，不真正入队",
    )

    @field_validator("input_path")
    @classmethod
    def _no_path_traversal(cls, v: str) -> str:
        # 防 path traversal：禁绝对路径与 `..`
        if v.startswith("/") or v.startswith("\\"):
            raise ValueError("input_path 不能以 / 或 \\ 开头")
        parts = v.split("/")
        if any(p == ".." for p in parts):
            raise ValueError("input_path 不允许包含 ..")
        return v


# ----------------------------------------------------------------
# 鉴权
# ----------------------------------------------------------------

def _check_ops_auth(x_ops_token: str = Header(default="")) -> None:
    """运维端点鉴权守卫。未开启 → 404；开启但 token 不对 → 403。"""
    if not settings.OPS_ENABLED:
        # fail-closed：对未授权使用者端点完全不存在
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="ops endpoint disabled",
        )
    expected = settings.OPS_TOKEN
    if not expected:
        # 没配 token 但开启了 → 同样 fail-closed
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="ops token not configured",
        )
    # 常量时间比较，防 timing 旁路
    if not secrets.compare_digest(
        (x_ops_token or "").encode("utf-8"),
        expected.encode("utf-8"),
    ):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="invalid ops token",
        )


# ----------------------------------------------------------------
# MinIO 探测（同步，probes only）
# ----------------------------------------------------------------

def _make_minio_client() -> Minio:
    endpoint = settings.MINIO_ENDPOINT.replace("http://", "").replace("https://", "")
    return Minio(
        endpoint,
        access_key=settings.MINIO_ACCESS_KEY,
        secret_key=settings.MINIO_SECRET_KEY,
        secure=settings.MINIO_ENDPOINT.startswith("https://"),
    )


def _probe_hls_files(video_id: int) -> Dict[str, Any]:
    """列 MinIO 上 videos/{id}/hls/*，并查 fingerprint.json 存在性。

    出错时返回带 minio_reachable=False 的字典，便于前端渲染降级提示。
    """
    out: Dict[str, Any] = {
        "video_id": video_id,
        "minio_reachable": True,
        "m3u8_exists": False,
        "segment_count": 0,
        "hls_files": [],
        "fingerprint_exists": False,
        "fingerprint_path": f"videos/{video_id}/fingerprint.json",
    }
    try:
        client = _make_minio_client()
        bucket = settings.MINIO_BUCKET_VIDEOS
        prefix = f"videos/{video_id}/hls/"
        hls_files: List[str] = []
        for obj in client.list_objects(bucket, prefix=prefix, recursive=True):
            hls_files.append(obj.object_name)
            if obj.object_name.endswith("playlist.m3u8"):
                out["m3u8_exists"] = True
        out["hls_files"] = hls_files
        out["segment_count"] = sum(1 for f in hls_files if f.endswith(".ts"))

        # fingerprint
        try:
            client.stat_object(bucket, out["fingerprint_path"])
            out["fingerprint_exists"] = True
        except S3Error:
            out["fingerprint_exists"] = False
    except S3Error as e:
        out["minio_reachable"] = False
        out["error"] = f"S3Error: {e.code if hasattr(e, 'code') else str(e)}"
    except Exception as e:
        # 网络错误 / DNS 解析失败等
        out["minio_reachable"] = False
        out["error"] = f"{type(e).__name__}: {e}"
    return out


# ----------------------------------------------------------------
# 端点
# ----------------------------------------------------------------

@router.get("/health")
async def ops_health() -> Dict[str, Any]:
    """运维侧活性探针：报告 ops 是否启用、token 是否配了。
    不需要 token，方便 K8s liveness probe 之类的场景。
    """
    return {
        "ops_enabled": settings.OPS_ENABLED,
        "token_configured": bool(settings.OPS_TOKEN),
        "minio_endpoint": settings.MINIO_ENDPOINT,
        "minio_bucket_videos": settings.MINIO_BUCKET_VIDEOS,
    }


@router.get("/videos/{video_id}/hls-status")
async def ops_hls_status(
    video_id: int,
    x_ops_token: str = Header(default=""),
) -> Dict[str, Any]:
    """探测 MinIO 上 `videos/{id}/hls/` 是否真有 m3u8/ts 与 fingerprint。

    用法：发现「视频无法播放」时先 GET 一下，
    若 m3u8_exists=false 则再 POST /reprocess-hls 入队重做。
    """
    _check_ops_auth(x_ops_token)
    return _probe_hls_files(video_id)


@router.post(
    "/videos/{video_id}/reprocess-hls",
    status_code=status.HTTP_202_ACCEPTED,
)
async def ops_reprocess_hls(
    video_id: int,
    body: ReprocessRequest,
    x_ops_token: str = Header(default=""),
) -> Dict[str, Any]:
    """重新把 video_id 投递到 q.video.preprocess 队列。

    走原 RabbitMQ 路径，复用 VideoProcessor.process()，所以水印、加密、
    MinIO 上传、backend 状态回调都和首次 upload 完全一致。
    """
    _check_ops_auth(x_ops_token)

    payload: Dict[str, Any] = {
        "video_id": video_id,
        "input_path": body.input_path,
        "classification_lv": body.classification_lv or "L2",
        "employee_no": body.employee_no or f"OPS_REPROCESS_{video_id}",
        # 打一个 ops 标识，便于 worker 在日志里追溯
        "ops_trigger": True,
    }

    if body.dry_run:
        return {
            "accepted": False,
            "dry_run": True,
            "video_id": video_id,
            "would_publish_payload": payload,
        }

    if _publisher is None:
        # worker 在 lifespan 启动之后才会注入；冷启动期间命中即 503
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="publisher not initialized (worker still starting?)",
        )

    try:
        await _publisher(payload)
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=f"failed to publish task: {type(e).__name__}: {e}",
        )

    return {
        "accepted": True,
        "video_id": video_id,
        "task_id": secrets.token_hex(8),
        "input_path": body.input_path,
        "routing_key": "video.preprocess",
    }
