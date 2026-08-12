# ============================================================================
# 前端镜像（Vue3 SPA）
# Stage 1: Node 18 构建
# Stage 2: Nginx 1.25 运行时
# ============================================================================

# ---------- Stage 1: Build ----------
FROM node:18-alpine AS builder

WORKDIR /build

# 复制 package 文件（缓存）
COPY web/package*.json ./
RUN npm ci --no-audit --no-fund

# 复制源码
COPY web/ ./

# 构建
ARG VITE_API_BASE_URL=https://api.example.com/api/v1
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL

RUN npm run build

# ---------- Stage 2: Runtime ----------
FROM nginx:1.25-alpine

# 安全配置
RUN addgroup -g 1001 -S edam && adduser -S edam -u 1001 -G edam

# 复制构建产物
COPY --from=builder /build/dist /usr/share/nginx/html

# 自定义 Nginx 配置
COPY <<EOF /etc/nginx/conf.d/default.conf
server {
    listen 80;
    server_name _;
    root /usr/share/nginx/html;
    index index.html;

    # SPA 路由
    location / {
        try_files \$uri \$uri/ /index.html;
    }

    # 静态资源缓存
    location ~* \.(js|css|png|jpg|svg|woff2?)$ {
        expires 7d;
        add_header Cache-Control "public, immutable";
    }

    # 安全头
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;

    # Gzip
    gzip on;
    gzip_types text/css application/javascript application/json image/svg+xml;
    gzip_min_length 1000;
}
EOF

# 健康检查
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
    CMD wget -q --spider http://localhost/ || exit 1

EXPOSE 80

# 非 root 启动 Nginx
RUN touch /var/run/nginx.pid && \
    chown -R edam:edam /var/cache/nginx /var/log/nginx /var/run/nginx.pid /usr/share/nginx/html

USER edam

CMD ["nginx", "-g", "daemon off;"]