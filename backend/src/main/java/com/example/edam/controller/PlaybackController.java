package com.example.edam.controller;

import com.example.edam.model.VideoResource;
import io.jsonwebtoken.Claims;
import com.example.edam.security.JwtTokenProvider;
import com.example.edam.audit.AuditAspect;
import com.example.edam.audit.AuditOp;
import com.example.edam.audit.AuditOpType;
import com.example.edam.service.VideoPermissionService;
import com.example.edam.service.VideoService;
import com.example.edam.security.CurrentUserId;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 视频播放与鉴权 Controller
 * 对应 openapi.yaml tag: playback
 */
@Slf4j
@RestController
@RequestMapping("/playback")
@RequiredArgsConstructor
public class PlaybackController {

    private final VideoService videoService;
    private final VideoPermissionService videoPermissionService;
    private final JwtTokenProvider jwtTokenProvider;
    private final MinioClient minioClient;
    private final com.example.edam.service.PlayLogService playLogService;

    @Value("${minio.bucket.videos}")
    private String videosBucket;

    @Value("${edam.hls.key-url-template}")
    private String keyUrlTemplate;

    /** {@code #EXT-X-KEY} 整行（multiline 模式；不含行尾的 \r\n） */
    private static final java.util.regex.Pattern EXT_X_KEY_LINE =
        java.util.regex.Pattern.compile("(?m)^(#EXT-X-KEY:[^\\r\\n]*)$");

    /**
     * EXT-X-KEY 行里的 URI 属性。
     *
     * <p>group(1)=分隔符（行首 / {@code :} / {@code ,}），
     * group(2)=属性名与等号，group(3)=属性值（带引号或不带）。
     */
    private static final java.util.regex.Pattern KEY_URI_ATTR =
        java.util.regex.Pattern.compile("(?i)(^|[:,])(\\s*URI\\s*=\\s*)(\"[^\"]*\"|[^,]*)");

    /** EXT-X-KEY 行里的 METHOD 属性（用来识别 {@code METHOD=NONE}） */
    private static final java.util.regex.Pattern KEY_METHOD_ATTR =
        java.util.regex.Pattern.compile("(?i)(?:^|[:,])\\s*METHOD\\s*=\\s*\"?([^\",]*)");

    /**
     * 获取播放 Token
     */
    @PostMapping("/{video_id}/token")
    @AuditOp(op = AuditOpType.PLAY, resourceType = "video", resourceId = "#videoId",
        detail = "event=start, classification_lv=#result?.body?.get('classification_lv')")
    public ResponseEntity<Map<String, Object>> getToken(
        @PathVariable("video_id") Long videoId,
        @CurrentUserId Long userId,
        HttpServletRequest request
    ) {
        VideoResource video = videoService.getById(videoId);

        // 可见性校验（密级 + 显式授权，见 VideoPermissionService）
        if (!videoPermissionService.canView(video, userId)) {
            log.warn("playback_token_denied, video_id={}, user_id={}, classification_lv={}",
                videoId, userId, video.getClassificationLv());
            Map<String, Object> body = new HashMap<>();
            body.put("error", "forbidden");
            body.put("detail", "没有该视频的查看权限（密级 " + video.getClassificationLv()
                + "，需由上传者授权）");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
        }


        // 1. 签发短时 JWT（**绑定到这个视频** —— 见 createPlaybackToken 的说明：
        //    m3u8/segment/key 是 permitAll，只靠 query token 鉴权，
        //    普通登录 token 能被拿来拉任意视频 ⇒ 播放 token 必须带 scope + video_id）
        String sessionId = UUID.randomUUID().toString();
        String token = jwtTokenProvider.createPlaybackToken(userId, videoId, sessionId);

        // 2. 生成签名 URL
        long expires = Instant.now().getEpochSecond() + 600;  // 10 分钟
        String m3u8Url = String.format("/api/v1/playback/%d/playlist.m3u8?token=%s",
            videoId, token);
        // segment URL 模板（给前端展示用，实际由 m3u8 内容决定）

        Map<String, Object> response = new HashMap<>();
        response.put("session_id", sessionId);
        response.put("m3u8_url", m3u8Url);
        response.put("token", token);
        response.put("expires_at", Instant.ofEpochSecond(expires).toString());
        response.put("key_url", String.format(keyUrlTemplate, videoId));
        response.put("watermark_template", "{employee_no}|{timestamp}");

        return ResponseEntity.ok(response);
    }

    /**
     * 获取 HLS playlist.m3u8（流式输出 MinIO 中的 m3u8，并把 segment 路径改写为后端代理路径）
     *
     * Hls.js 不能直接拉 MinIO 的 HLS（缺少 Authorization 头），
     * 所以后端代理出 m3u8 + segments，URL 里带 token。
     *
     * 鉴权：dev 阶段 permitAll + 方法内校验 JWT（防止 Hls.js 自动请求时不带 Authorization）。
     * 生产：建议换 presigned MinIO URL（5 分钟过期），无需后端代理。
     */
    @GetMapping(value = "/{video_id}/playlist.m3u8", produces = "application/vnd.apple.mpegurl")
    @AuditOp(op = AuditOpType.PLAY, resourceType = "video", resourceId = "#videoId",
        detail = "event=playlist")
    public ResponseEntity<byte[]> getPlaylist(
        @PathVariable("video_id") Long videoId,
        @RequestParam("token") String token,
            jakarta.servlet.http.HttpServletRequest request) {
        // 1. 验证 token（不依赖 Spring Security context，query string 取）
        //
        // 必须校验 token 是**绑定到本视频的播放 token**：这三个端点是 permitAll，
        // 只靠 query token 鉴权 —— 只验签的话，普通登录 token 也能拉任意视频。
        //
        // 先验 token 再打日志：验不过的请求也要留下痕迹，但**不能记 token 本身**
        // （连哈希都不打 —— 见项目安全约定），只记 video_id。
        io.jsonwebtoken.Claims claims;
        try {
            claims = jwtTokenProvider.parseAndValidate(token);
        } catch (Exception e) {
            log.warn("m3u8_token_invalid, video_id={}, error={}", videoId, e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!jwtTokenProvider.isPlaybackTokenFor(claims, videoId)) {
            log.warn("m3u8_token_not_for_this_video, video_id={}", videoId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String requesterId = subjectOf(claims);
        log.info("m3u8_endpoint_hit, video_id={}, requester_id={}, token_len={}, remote_addr={}, x_forwarded_host={}",
            videoId, requesterId, token.length(), request.getRemoteAddr(), request.getHeader("X-Forwarded-Host"));

        String objectName = String.format("videos/%d/hls/playlist.m3u8", videoId);
        try {
            byte[] content;
            // try-with-resources：原来 readAllBytes() 抛异常时 close() 永不执行，
            // MinIO 的 HTTP 连接直接泄漏，并发播放出错时会耗尽连接池
            try (InputStream stream = minioClient.getObject(
                    GetObjectArgs.builder().bucket(videosBucket).object(objectName).build())) {
                content = stream.readAllBytes();
            }

            // 把 segment_001.ts 等相对路径改写为**根相对路径**（/api/v1/...）
            // 同时把 EXT-X-KEY 的 URI="key.bin" 改写为后端 key 端点（带 token）
            //
            // 为什么是"根相对"而不是"绝对路径"：
            // 绝对路径需要先猜出浏览器侧的 origin（scheme + host + port），
            // 而这个猜测**不可靠** —— nginx 的 `proxy_set_header X-Forwarded-Host $host`
            // 里 `$host` **不含端口**（含端口的是 `$http_host`），
            // 于是 `https://host:65173` 会被猜成 `https://host`（丢了 65173）。
            // 猜错的后果：m3u8 本身能下（它是同源相对路径），
            // 但 m3u8 里的分片/密钥地址指向了另一个 origin/端口 ⇒ 拉不到 ⇒
            // hls.js 拿不到任何 HTTP 响应（超时）⇒ 前端只能报 "HTTP ??"。
            //
            // 根相对路径由 **hls.js 用 manifest 自身的 URL** 解析：
            // manifest 是浏览器自己按同源拉下来的，它的 origin/端口**一定是对的**，
            // 所以分片/密钥地址也就一定对 —— 不依赖后端猜端口。
            String text = new String(content, java.nio.charset.StandardCharsets.UTF_8);
            String rewritten = rewriteM3u8(text, videoId, token);

            log.info("m3u8_served, video_id={}, requester_id={}, segments_count={}, has_ext_x_key={}, ext_x_key_uri={}, in_size={}, out_size={}",
                videoId, requesterId, countLines(text, ".ts"), text.contains("#EXT-X-KEY"),
                originalKeyUri(text), content.length,
                rewritten.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);

            return ResponseEntity.ok()
                .header("Content-Type", "application/vnd.apple.mpegurl")
                .header("Cache-Control", "no-store, max-age=0")
                .body(rewritten.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 区分两种 404：
            //   - MinIO 里真的没这个对象（转码没跑 / 产物被删）
            //   - MinIO 本身连不上（网络/凭据问题）
            // 原来一律 404，把"对象存储挂了"也报成"文件不存在"，
            // 运维查 MinIO 时会被带偏。
            if (isMinioUnreachable(e)) {
                log.error("m3u8_serve_failed_minio_unreachable, video_id={}", videoId, e);
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }
            log.error("m3u8_serve_failed, video_id={}, object={}", videoId, objectName, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    /**
     * 判断异常是不是"MinIO 本身不可达"（而不是"对象不存在"）。
     *
     * <p>MinIO 对"对象不存在"抛 {@code ErrorResponseException}，
     * code = {@code NoSuchKey}；对"连不上 / 5xx / 鉴权失败"抛别的异常
     * （{@code ServerException} / {@code InsufficientDataException} /
     * {@code IOException} 之类）。
     */
    private boolean isMinioUnreachable(Exception e) {
        if (e instanceof io.minio.errors.ErrorResponseException) {
            String code = ((io.minio.errors.ErrorResponseException) e).errorResponse().code();
            // NoSuchKey / NoSuchBucket 之外的 S3 错误码更可能是服务端问题
            return code == null || !("NoSuchKey".equals(code) || "NoSuchBucket".equals(code));
        }
        // 连接类异常（UnknownHost / ConnectException / SocketTimeout …）
        return e instanceof java.io.IOException
            || e.getCause() instanceof java.io.IOException;
    }

    /**
     * HLS segment（.ts）代理
     */
    @GetMapping(value = "/{video_id}/segment/{filename:.+}", produces = "video/mp2t")
    @AuditOp(op = AuditOpType.PLAY, resourceType = "video", resourceId = "#videoId",
        detail = "event=segment, file=#filename", auditSuccess = false)
    public ResponseEntity<byte[]> getSegment(
        @PathVariable("video_id") Long videoId,
        @PathVariable("filename") String filename,
        @RequestParam("token") String token) {
        // 1. 验证 token（必须是绑定到本视频的播放 token —— 见 getPlaylist 的说明）
        Claims claims;
        try {
            claims = jwtTokenProvider.parseAndValidate(token);
        } catch (Exception e) {
            log.warn("segment_token_invalid, video_id={}, file={}, error={}",
                videoId, filename, e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!jwtTokenProvider.isPlaybackTokenFor(claims, videoId)) {
            log.warn("segment_token_not_for_this_video, video_id={}, file={}", videoId, filename);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String requesterId = subjectOf(claims);

        // 安全检查：filename 只能包含 segment_*.ts 这种
        if (!filename.matches("segment_\\d+\\.ts")) {
            log.warn("segment_filename_invalid, video_id={}, filename={}", videoId, filename);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        try {
            String objectName = String.format("videos/%d/hls/%s", videoId, filename);
            byte[] content;
            try (InputStream stream = minioClient.getObject(
                    GetObjectArgs.builder().bucket(videosBucket).object(objectName).build())) {
                content = stream.readAllBytes();
            }
            return ResponseEntity.ok()
                .header("Content-Type", "video/mp2t")
                .header("Cache-Control", "public, max-age=300")
                .body(content);
        } catch (Exception e) {
            // 带上请求者：同一个视频被不同账号播放时，出问题时能区分是谁的请求
            log.error("segment_serve_failed, video_id={}, file={}, requester_id={}",
                videoId, filename, requesterId, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    /**
     * 把 m3u8 改写成 Hls.js 可直接消费的形态：
     * 1. segment_001.ts 等相对路径 → **根相对路径** {@code /api/v1/playback/{id}/segment/...}（带 token）
     * 2. EXT-X-KEY 的 URI="key.bin" → 后端 key 端点根相对地址（带 token）
     *
     * <p><b>为什么用根相对路径</b>（而不是早期版本用的绝对路径）：
     * 绝对路径要后端先猜出浏览器侧的 origin（scheme + host + **port**），
     * 而这个猜测在反向代理下**不可靠**：
     * nginx 的 {@code proxy_set_header X-Forwarded-Host $host} 里，
     * {@code $host} **不含端口**（含端口的是 {@code $http_host}），
     * 于是 {@code https://host:65173} 被猜成 {@code https://host} —— 端口丢了。
     *
     * <p>丢端口的后果很隐蔽：manifest 本身是同源相对路径，能正常下载；
     * 但 m3u8 里的分片/密钥地址指向了另一个 origin ⇒ 浏览器连不上 ⇒
     * hls.js **收不到任何 HTTP 响应**（只能等超时）⇒ 前端只能报 {@code HTTP ??}。
     *
     * <p>根相对路径由 hls.js 用 **manifest 自身的 URL** 解析 ——
     * manifest 是浏览器按同源拉下来的，它的 origin 和端口**必然正确**，
     * 所以分片/密钥地址也必然正确。后端不再需要猜端口。
     */
    private String rewriteM3u8(String m3u8, long videoId, String token) {
        // 匹配 segment_xxx.ts 整行（不含 #EXTINF / #EXT-X-*）
        // token 用 quoteReplacement 包一下：replaceAll 的替换串里 $ 和 \ 有特殊含义，
        // JWT 里虽然不会出现这两个字符，但别让"凭据格式变化"变成一次线上事故。
        String rewritten = m3u8.replaceAll(
            "(?m)^(segment_\\d+\\.ts)([ \\t]*\\r?)$",
            "/api/v1/playback/" + videoId + "/segment/$1$2?token="
                + java.util.regex.Matcher.quoteReplacement(token));
        // EXT-X-KEY 的 URI 一律换成后端 key 端点 —— Hls.js 不能带自定义头，所以走 query token
        return rewriteKeyUris(rewritten,
            String.format("/api/v1/playback/%d/key?token=%s", videoId, token));
    }

    /**
     * 把播放列表里 {@code #EXT-X-KEY} 行的 URI 属性换成后端 key 端点。
     *
     * <p><b>为什么不能再只认 {@code URI="key.bin"}</b>（2026-11-03 线上故障的根因）：
     * 存量播放列表里的 EXT-X-KEY 有好几种形态，而**只有一种**能被老正则改写：
     *
     * <table border="1">
     *   <caption>存量播放列表里出现过的 EXT-X-KEY 形态</caption>
     *   <tr><th>形态</th><th>来源</th><th>老正则的结果</th></tr>
     *   <tr><td>{@code URI="key.bin"}</td><td>Worker 正常产物</td><td>改写成功</td></tr>
     *   <tr><td>{@code URI="42ccf808…"}</td><td>2026-10-29/30 的 key.info
     *       "假成功"（FFmpeg 成功退出但把 key 的十六进制当 URI 写进 m3u8）</td>
     *       <td>不改写 ⇒ hls.js 拿这个当相对路径去请求后端 ⇒ 404</td></tr>
     *   <tr><td>{@code URI="https://api.example.com/…"}</td><td>更早的 Worker
     *       把绝对地址烤进了 m3u8</td><td>不改写 ⇒ 浏览器去连 example.com ⇒ 连不上</td></tr>
     *   <tr><td>{@code URI=""} / 根本没有 URI 属性</td><td>手工改过 / 别的工具产物</td>
     *       <td>不改写 ⇒ hls.js 解析出**空**的 key 地址 ⇒
     *           {@code Invalid key URI} ⇒ <b>连一次 HTTP 请求都不会发</b></td></tr>
     * </table>
     *
     * <p>最后一行对应用户看到的报错形态：hls.js 抛的是
     * {@code keyLoadError} 且**不带任何 HTTP 响应**（前端因此显示
     * "无响应（超时或连接被拒）"）—— 因为它压根没发请求。
     *
     * <p>本项目的播放列表**每个视频只有一个密钥**（{@code videos/{id}/key.bin}），
     * 所以所有 EXT-X-KEY 行都指向同一个后端端点是对的；
     * {@code METHOD=NONE} 的行表示"这段不加密"，必须原样保留。
     */
    private String rewriteKeyUris(String m3u8, String keyUrl) {
        java.util.regex.Matcher lines = EXT_X_KEY_LINE.matcher(m3u8);
        StringBuilder out = new StringBuilder();
        while (lines.find()) {
            String rewrittenLine = rewriteKeyLine(lines.group(1), keyUrl);
            lines.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(rewrittenLine));
        }
        lines.appendTail(out);
        return out.toString();
    }

    /**
     * 改写单行 EXT-X-KEY：把 URI 属性换成后端 key 端点。
     *
     * <p>三种输入都要能处理：URI 已有值（不论是什么值）、URI 为空、根本没有 URI 属性。
     * 其它属性（{@code IV} / {@code KEYFORMAT} 等）原样保留。
     */
    private String rewriteKeyLine(String line, String keyUrl) {
        // METHOD=NONE ⇒ 这一段不加密，别碰它
        java.util.regex.Matcher method = KEY_METHOD_ATTR.matcher(line);
        if (method.find() && "NONE".equalsIgnoreCase(method.group(1).trim())) {
            return line;
        }
        java.util.regex.Matcher uri = KEY_URI_ATTR.matcher(line);
        if (uri.find()) {
            // group(1) 是分隔符（行首 / ':' / ','），保留它，避免把两个属性粘在一起
            return line.substring(0, uri.start())
                + uri.group(1) + "URI=\"" + keyUrl + "\""
                + line.substring(uri.end());
        }
        // 压根没有 URI 属性 ⇒ 补一个（否则 hls.js 解析出空的 key 地址）
        return line + ",URI=\"" + keyUrl + "\"";
    }

    /**
     * 取播放列表里 EXT-X-KEY 行的**原始** URI 值，只为打日志。
     *
     * <p>这个值决定了故障的形态（十六进制 / 绝对地址 / 空），
     * 但它同时也是"MinIO 里到底存了什么"的唯一证据 ——
     * 不打出来，运维就只能猜。
     */
    private String originalKeyUri(String m3u8) {
        java.util.regex.Matcher lines = EXT_X_KEY_LINE.matcher(m3u8);
        if (!lines.find()) {
            return "(无 EXT-X-KEY 行)";
        }
        java.util.regex.Matcher uri = KEY_URI_ATTR.matcher(lines.group(1));
        if (!uri.find()) {
            return "(EXT-X-KEY 行里没有 URI 属性)";
        }
        String raw = uri.group(3).trim();
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        if (raw.isEmpty()) {
            return "(空)";
        }
        // 日志里不得出现 token
        return raw.replaceAll("([?&]token=)[^&]*", "$1***");
    }

    private int countLines(String text, String ext) {
        return (int) java.util.Arrays.stream(text.split("\n"))
            .filter(l -> l.trim().endsWith(ext))
            .count();
    }

    /**
     * 获取 HLS AES-128 密钥
     *
     * 鉴权：query string token（与 playlist.m3u8 / segment 同一套机制）。
     * Hls.js 拉 key 时**不会带自定义请求头**，所以不能用
     * {@code @RequestHeader("X-Session-Token")} —— 那样会直接 400。
     *
     * 密钥来源：Worker 转码时生成的 16 字节随机 key，存在 MinIO
     * {@code videos/{id}/key.bin}（刻意放在 hls/ 目录之外，
     * 避免被 segment 代理暴露）。
     */
    @GetMapping("/{video_id}/key")
    @AuditOp(op = AuditOpType.PLAY, resourceType = "video", resourceId = "#videoId",
        detail = "event=key")
    public ResponseEntity<byte[]> getKey(
        @PathVariable("video_id") Long videoId,
        @RequestParam("token") String token
    ) {
        Claims claims;
        try {
            claims = jwtTokenProvider.parseAndValidate(token);
        } catch (Exception e) {
            log.warn("key_token_invalid, video_id={}, error={}", videoId, e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!jwtTokenProvider.isPlaybackTokenFor(claims, videoId)) {
            log.warn("key_token_not_for_this_video, video_id={}", videoId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String requesterId = subjectOf(claims);

        String objectName = String.format("videos/%d/key.bin", videoId);
        long startedAt = System.nanoTime();
        try (InputStream stream = minioClient.getObject(
                GetObjectArgs.builder().bucket(videosBucket).object(objectName).build())) {
            byte[] key = stream.readAllBytes();
            if (key.length != 16) {
                log.error("hls_key_invalid_length, video_id={}, len={}", videoId, key.length);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
            // key_fp = 密钥 SHA-256 的前 8 个十六进制字符。
            //
            // 为什么可以打：它是 128 位随机密钥的**单向摘要前缀**，
            // 不能反推出密钥本身（项目安全约定禁止的是把凭据本身写进日志，
            // 摘要不是凭据）。打它的用处很实在：
            //  - 同一视频两次播放拿到的 key 是不是同一份（不一致 ⇒ 转码换过密钥）
            //  - 播放器报的解密失败，是不是"密钥与分片不匹配"
            // 没有它，这两件事只能靠猜。
            log.info("hls_key_served, video_id={}, requester_id={}, key_fp={}, read_ms={}",
                videoId, requesterId, fingerprint(key), (System.nanoTime() - startedAt) / 1_000_000);
            return ResponseEntity.status(HttpStatus.OK)
                .header("Cache-Control", "no-store")
                .header("Content-Type", "application/octet-stream")
                .body(key);
        } catch (Exception e) {
            // 区分两种失败（和 getPlaylist 同一个道理）：
            //   - MinIO 里真的没这个对象（转码没跑 / 产物被删）⇒ 404
            //   - MinIO 本身连不上（网络/凭据问题）⇒ 503
            // 一律报 404 会让运维以为"密钥文件被删了"，去查文件，
            // 而真正该查的是 MinIO 连不连得上。
            if (isMinioUnreachable(e)) {
                log.error("hls_key_serve_failed_minio_unreachable, video_id={}, object={}, requester_id={}",
                    videoId, objectName, requesterId, e);
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }
            log.error("hls_key_serve_failed, video_id={}, object={}, requester_id={}",
                videoId, objectName, requesterId, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    /**
     * 密钥指纹：SHA-256 的前 8 个十六进制字符。
     *
     * <p>只用于日志比对（见 {@link #getKey} 里的说明），不是凭据。
     */
    private String fingerprint(byte[] key) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(key);
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "(no-sha256)";
        }
    }

    /**
     * 从已解析的 claims 里取请求者 id（= JWT 的 subject），取不到就返回 null。
     *
     * <p>用于日志：同一个视频可能被多个账号播放，出问题时得能区分是谁的请求。
     * **只取 subject，不打 token 本身**（项目安全约定：日志里不得出现 token）。
     */
    private String subjectOf(Claims claims) {
        return claims == null ? null : claims.getSubject();
    }

    /**
     * 上报播放日志
     */
    @PostMapping("/{video_id}/log")
    @AuditOp(op = AuditOpType.PLAY, resourceType = "video", resourceId = "#videoId",
        detail = "event=#request.event, progress_sec=#request.progressSec")
    public ResponseEntity<Void> reportLog(
        @PathVariable("video_id") Long videoId,
        @RequestBody PlaybackLogRequest request,
        @CurrentUserId Long userId,
        HttpServletRequest httpRequest
    ) {
        // 验证 session
        if (request.getSessionId() == null || request.getSessionId().isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // 写 play_log（方案书 §7.1 播放日志表；§7.3 保留 365 天）
        //
        // 这是**溯源链的最后一环**：operation_log 记"谁点了播放"，
        // play_log 记"谁把哪个视频看了多久、看到哪儿、期间有没有水印"。
        // 视频外泄时，靠它把"某工号在某个时间点看完了内部视频"这条证据固化下来。
        playLogService.record(
            userId, videoId, request.getSessionId(),
            request.getEvent(), request.getProgressSec(),
            clientIpOf(httpRequest), userAgentOf(httpRequest),
            request.getWatermarkApplied());

        return ResponseEntity.noContent().build();
    }

    /** 真实客户端 IP（X-Forwarded-For → X-Real-IP → RemoteAddr） */
    private String clientIpOf(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            String first = (comma > 0 ? xff.substring(0, comma) : xff).trim();
            if (!first.isEmpty() && !"unknown".equalsIgnoreCase(first)) {
                return first;
            }
        }
        String xri = request.getHeader("X-Real-IP");
        if (xri != null && !xri.isBlank()) {
            return xri.trim();
        }
        return request.getRemoteAddr();
    }

    /** User-Agent（截断到 200 字符） */
    private String userAgentOf(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String ua = request.getHeader("User-Agent");
        if (ua == null) {
            return "unknown";
        }
        return ua.length() > 200 ? ua.substring(0, 200) : ua;
    }

    @Data
    public static class PlaybackLogRequest {
        private String sessionId;
        private String event;
        private String timestamp;
        private Integer progressSec;
        /** 客户端是否已叠加动态水印（溯源时用来判断"能不能指认到人"） */
        private Boolean watermarkApplied;
    }
}