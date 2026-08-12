# ============================================================================
# Python Worker 镜像
# 包含：Python 3.10 + FFmpeg + LibreOffice + 国密 + 视频/图像处理库
# ============================================================================

FROM python:3.10-slim-bookworm AS builder

# 安装系统依赖
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg libreoffice-core libreoffice-writer libreoffice-calc \
    libsm6 libxext6 libxrender1 libgl1 libglib2.0-0 \
    gcc g++ make pkg-config \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build

# Python 依赖（利用缓存层）
COPY worker/requirements.txt .
RUN pip install --no-cache-dir --user -r requirements.txt

# 复制源码
COPY worker/src ./src

# ---------- Runtime ----------
FROM python:3.10-slim-bookworm AS runtime

# 系统依赖
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg libreoffice-core libreoffice-writer libreoffice-calc \
    libsm6 libxext6 libxrender1 libgl1 libglib2.0-0 \
    tini curl ca-certificates tzdata \
    && rm -rf /var/lib/apt/lists/* && \
    ln -snf /usr/share/zoneinfo/UTC /etc/localtime

# 非 root 用户
RUN groupadd -r -g 1000 edam && useradd -r -u 1000 -g edam -d /app -s /sbin/nologin edam

# 从 builder 复制 Python 依赖
COPY --from=builder --chown=edam:edam /root/.local /home/edam/.local

WORKDIR /app
COPY --from=builder --chown=edam:edam /build/src /app/src
RUN touch /app/__init__.py

ENV PATH=/home/edam/.local/bin:$PATH \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app/src \
    LOG_LEVEL=INFO

USER edam

HEALTHCHECK --interval=30s --timeout=5s --retries=3 --start-period=30s \
    CMD curl -fsS http://localhost:8001/health/live || exit 1

EXPOSE 8001

ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["python", "-m", "uvicorn", "edam_worker.main:app", "--host", "0.0.0.0", "--port", "8001"]