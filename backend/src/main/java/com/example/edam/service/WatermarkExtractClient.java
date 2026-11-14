package com.example.edam.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 盲水印提取客户端（Backend → Worker）。
 *
 * <p><b>为什么要有这个类</b>：盲水印的**提取**算法只在 Worker 侧
 * （{@code processors/watermark.py} 的 {@code WatermarkProcessor.extract}，
 * 用 blind-watermark / PyPDF2 从频域里把水印读出来）。
 * Java 后端没有对应实现 —— 此前 {@code POST /watermarks/extract}
 * 返回的是一个**假的**"没查到泄露"（{@code confidence=0.0} +
 * {@code matched_users=[]}）。
 *
 * <p>那比不提供这个端点更危险：运维看到"没有匹配用户"，
 * 会得出"这个文件是干净的"这个**错误结论**。
 *
 * <p>现在改为真的调 Worker 提取。Worker 不可达 / 未配置地址时，
 * 调用方会拿到一个**明确的错误**，而不是"没查到泄露"。
 *
 * <p>鉴权：请求带 {@code X-Worker-Token} —— 与后端回调 Worker 的方向相反，
 * 但用同一个共享密钥（Worker 侧 fail-closed：没配就一律 403）。
 */
@Slf4j
@Component
public class WatermarkExtractClient {

    /** Worker 的地址，如 {@code http://edam-worker:8001}（不带尾斜杠） */
    @Value("${edam.worker.base-url:}")
    private String workerBaseUrl;

    /** 与 Worker 共享的密钥；为空则不调用（fail-closed） */
    @Value("${edam.worker.token:}")
    private String workerToken;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * 让 Worker 提取一段字节里的盲水印。
     *
     * @param content  文件原始字节
     * @param fileType 文件类型（image / pdf）
     * @return 提取结果
     * @throws WorkerUnavailableException Worker 不可达 / 未配置 / 返回异常
     */
    public ExtractResult extract(byte[] content, String fileType) {
        if (workerBaseUrl == null || workerBaseUrl.isBlank()) {
            throw new WorkerUnavailableException(
                "未配置 edam.worker.base-url，无法调用 Worker 提取盲水印");
        }
        if (workerToken == null || workerToken.isBlank()) {
            // fail-closed：没有共享密钥就不发 —— Worker 侧也会拒，
            // 但不必浪费一次往返，更不必把 token 空着发出去
            throw new WorkerUnavailableException(
                "未配置 edam.worker.token，无法调用 Worker 提取盲水印");
        }

        // file_type 拼进 query —— 必须转义。
        // 调用方（WatermarkController）已经把它限死在 {image, pdf}，
        // 但这一层不能依赖"上游一定会检查"：
        // 未转义的原始字符串能让调用方往 query 里塞 & / # 之类的东西，
        // 也可能让 URI.create 直接抛 IllegalArgumentException（500）。
        String url = workerBaseUrl.replaceAll("/+$", "")
                + "/internal/watermark/extract?file_type="
                + java.net.URLEncoder.encode(fileType == null ? "" : fileType,
                    StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/octet-stream")
                .header("X-Worker-Token", workerToken)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofByteArray(content))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkerUnavailableException("调用 Worker 提取盲水印被中断: " + e.getMessage());
        } catch (Exception e) {
            log.warn("watermark_extract_worker_unreachable url={} error={}", url, e.getMessage());
            throw new WorkerUnavailableException("调用 Worker 提取盲水印失败: " + e.getMessage());
        }

        if (response.statusCode() != 200) {
            // 不打印响应体里的敏感内容，只留前 200 字符便于排障
            log.warn("watermark_extract_worker_error status={} body={}",
                response.statusCode(), snippet(response.body()));
            throw new WorkerUnavailableException(
                "Worker 返回 HTTP " + response.statusCode() + "：" + snippet(response.body()));
        }

        return parse(response.body());
    }

    /**
     * 解析 Worker 的 JSON 响应（只取需要的字段，不引 JSON 依赖）。
     */
    private ExtractResult parse(String body) {
        String text = extractJsonString(body, "text");
        boolean extracted = body != null && body.contains("\"extracted\":true");
        return new ExtractResult(extracted, text == null ? "" : text);
    }

    private String extractJsonString(String body, String key) {
        if (body == null) return null;
        String needle = "\"" + key + "\"";
        int k = body.indexOf(needle);
        if (k < 0) return null;
        int colon = body.indexOf(':', k + needle.length());
        if (colon < 0) return null;
        int q1 = body.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = body.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return body.substring(q1 + 1, q2);
    }

    private String snippet(String body) {
        if (body == null) return "";
        return body.length() > 200 ? body.substring(0, 200) + "…" : body;
    }

    /**
     * 提取结果。
     */
    public record ExtractResult(boolean extracted, String text) {
    }

    /**
     * Worker 不可用（未配置 / 连不上 / 返回错误）。
     *
     * <p>与"没有提取到水印"是**两回事** —— 前者是系统故障，后者是有效结论。
     */
    public static class WorkerUnavailableException extends RuntimeException {
        public WorkerUnavailableException(String message) {
            super(message);
        }
    }
}
