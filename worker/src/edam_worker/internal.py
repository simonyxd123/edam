"""
EDAM Worker 内网接口（Backend → Worker）

与 `/ops` 的区别：
- `/ops` 是**运维**接口，给人用（人工重跑转码、人工比对泄露），
  鉴权用 `OPS_TOKEN`，默认关闭
- `/internal` 是**系统**接口，给后端调用，鉴权用 `WORKER_TOKEN`
  —— 与后端回调 Worker 的方向相反但用同一个共享密钥

**为什么需要它**：盲水印的**提取**算法在 Worker 侧
（`processors/watermark.py` 的 `WatermarkProcessor.extract`，
用 blind-watermark / PyPDF2 提取频域水印），
而 Java 后端没有对应实现 —— 后端此前只能返回一个**假的**"没查到泄露"，
比不提供更危险：运维会据此认为"这个文件是干净的"。

失败模式（与 ops 一致的 fail-closed 约定）：
- `WORKER_TOKEN` 为空          → 一律 403（fail-closed）
- token 不匹配                 → 403
- 请求体不是合法文件 / 类型不支持 → 400（**明确报错**，不返回"没提取到"）
"""

import os
import secrets
import tempfile
from typing import Any, Dict, Optional

from fastapi import APIRouter, Body, Depends, Header, HTTPException, Query, status
from pydantic import BaseModel, Field

from .config import settings

router = APIRouter(prefix="/internal", tags=["internal"])

# 提取的体积上限 —— 与后端文档上传限制一致（50MB）
MAX_EXTRACT_BYTES = 50 * 1024 * 1024


class ExtractResponse(BaseModel):
    """盲水印提取结果"""

    extracted: bool = Field(default=False, description="是否提取到水印")
    text: str = Field(default="", description="提取到的水印文本")
    file_type: str = Field(default="", description="文件类型")
    detail: str = Field(default="", description="提取过程的说明 / 失败原因")


def _check_internal_auth(x_worker_token: str = Header(default="")) -> None:
    """内网端点鉴权守卫。没配 WORKER_TOKEN → 403（fail-closed）。"""
    expected = settings.WORKER_TOKEN
    if not expected:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="worker token not configured",
        )
    if not secrets.compare_digest(
        (x_worker_token or "").encode("utf-8"),
        expected.encode("utf-8"),
    ):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="invalid worker token",
        )


# 鉴权做成**依赖**而不是端点里的第一行 ——
# FastAPI 先跑依赖、再读 body。如果把鉴权写在函数体里，
# 一个未鉴权的调用者仍能让服务端把整个请求体缓冲完才被拒 ——
# 对"上传文件做提取"这种端点，那就是个放大攻击面。
def _require_internal_auth(
    x_worker_token: str = Header(default=""),
    content_length: Optional[int] = Header(default=None, alias="content-length"),
) -> None:
    """鉴权 + 大小上限（依赖形式，先于 body 字段校验执行）。"""
    _check_internal_auth(x_worker_token)
    # 上限与后端文档上传限制一致（50MB）。
    # 提取的是"疑似泄露的单文件"，不该比正常上传还大。
    if content_length is not None and content_length > MAX_EXTRACT_BYTES:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=(
                f"文件过大（{content_length} 字节），"
                f"提取上限 {MAX_EXTRACT_BYTES} 字节"
            ),
        )


@router.post("/watermark/extract", response_model=ExtractResponse)
async def internal_watermark_extract(
    file_type: str = Query(..., description="image / pdf / docx / xlsx"),
    body: Optional[bytes] = Body(default=None, media_type="application/octet-stream"),
    _auth: None = Depends(_require_internal_auth),
) -> Dict[str, Any]:
    """提取文件里的盲水印。

    请求体是**文件原始字节**（不是 MinIO 路径）——
    后端把用户上传的疑似泄露文件直接送过来，不用先落 MinIO，
    避免把溯源样本污染进对象存储。

    提取不到水印时返回 `extracted=false`（**不是**报错）——
    "这个文件里没有水印"本身就是一个有效结论。
    但**提取过程出错**（算法缺失、文件损坏）必须报错，
    不能伪装成"没提取到"。
    """
    supported = {"image", "pdf"}
    if file_type not in supported:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=(
                f"暂不支持从 {file_type!r} 提取水印；"
                f"当前支持 {sorted(supported)}"
                "（Office 的水印是隐写，提取算法未实现）"
            ),
        )
    if not body:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="请求体为空",
        )

    from .processors.watermark import WatermarkProcessor

    work_dir = tempfile.mkdtemp(prefix="edam-extract-")
    local_path = os.path.join(work_dir, "sample")
    try:
        with open(local_path, "wb") as f:
            f.write(body)

        try:
            text = WatermarkProcessor().extract(local_path, file_type)
        except Exception as e:
            # 提取过程出错 ≠ 没有水印 —— 必须让后端知道
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"watermark extraction failed: {type(e).__name__}: {e}",
            )
    finally:
        import shutil
        shutil.rmtree(work_dir, ignore_errors=True)

    text = (text or "").strip()
    return {
        "extracted": bool(text),
        "text": text,
        "file_type": file_type,
        "detail": "" if text else "该文件里没有提取到盲水印",
    }
