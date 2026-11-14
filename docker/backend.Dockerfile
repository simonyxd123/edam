# ============================================================================
# 多阶段构建：Spring Boot 后端
# Stage 1: 构建（包含 Maven + JDK）
# Stage 2: 运行时（仅 JRE）
# ============================================================================

# ---------- Stage 1: Build ----------
FROM eclipse-temurin:17-jdk-jammy AS builder

# 避免 Maven 下载缓慢，使用阿里云镜像
ENV MAVEN_OPTS="-Dmaven.repo.local=/root/.m2/repository"

WORKDIR /build

# 先复制 pom.xml 利用缓存
COPY backend/pom.xml .
RUN --mount=type=cache,target=/root/.m2/repository \
    apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl && \
    curl -fsSL https://repo.huaweicloud.com/repository/maven/ -o /dev/null || true

# 下载依赖（利用 layer 缓存）
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -e -ntp -DskipTests dependency:go-offline

# 复制源码并构建
COPY backend/src ./src
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -e -ntp -DskipTests package

# ---------- Stage 2: Runtime ----------
FROM eclipse-temurin:17-jre-jammy

# 安全：非 root 运行
RUN groupadd -r -g 1000 edam && useradd -r -u 1000 -g edam -d /app -s /sbin/nologin edam

# 安装必要工具
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl tini ca-certificates tzdata && \
    rm -rf /var/lib/apt/lists/* && \
    ln -snf /usr/share/zoneinfo/UTC /etc/localtime

WORKDIR /app

# 复制构建产物
COPY --from=builder --chown=edam:edam /build/target/edam-backend.jar /app/edam-backend.jar

# 健康检查
#
# 端口必须是 **8092**（全链路统一端口，见 backend/src/main/resources/application.yml
# 的 `server.port: 8092`、CLAUDE.md「端口全链路统一 8092」）。
#
# 这里此前写的是 8080 ⇒ healthcheck **永远失败** ⇒ 容器被标 unhealthy
# ⇒ `depends_on: condition: service_healthy` 的编排会永远等下去。
#
# 为什么 K8s 生产没事：K8s 用的是 helm/edam/templates/deployment.yaml 里的
# livenessProbe/readinessProbe（走 backend.service.port，值是对的），
# Dockerfile 的 HEALTHCHECK 在 K8s 下被忽略。
# 但"直接 docker run 后端镜像"这条路会炸 —— 而 CI 的 docker-build job 正是干这个的。
#
# context-path 是 /api/v1（application.yml 的 server.servlet.context-path），
# 所以健康检查路径带 /api/v1 前缀。
HEALTHCHECK --interval=30s --timeout=5s --retries=3 --start-period=60s \
    CMD curl -fsS http://localhost:8092/api/v1/health/live || exit 1

USER edam

# JVM 参数（生产）
ENV JAVA_OPTS="\
-XX:+UseG1GC \
-XX:MaxGCPauseMillis=200 \
-XX:+UseStringDeduplication \
-XX:+HeapDumpOnOutOfMemoryError \
-XX:HeapDumpPath=/tmp \
-Xms512m -Xmx2g \
-Duser.timezone=UTC \
-Dfile.encoding=UTF-8"

# 对外端口 8092（与 server.port 一致，见上面 HEALTHCHECK 的说明）
EXPOSE 8092

# tini 正确处理信号
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar /app/edam-backend.jar"]