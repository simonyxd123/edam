package com.example.edam.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Worker 回调令牌校验器。
 *
 * <p><b>为什么要抽出来</b>：Worker → Backend 的回调端点（视频状态、文档状态）
 * 都必须在方法内部校验 {@code X-Worker-Token}。这类代码**不能复制两份** ——
 * 复制之后改了一处忘了另一处，就是一个静默敞开的后门。
 *
 * <p><b>fail-closed</b>：后端没配 {@code edam.worker.token} 时一律拒绝 ——
 * 不能因为"忘了配"就把端点敞开。
 *
 * <p>比较用 {@link MessageDigest#isEqual}（常量时间），防 timing 旁路。
 *
 * <p>不打印 token 本身（连哈希都不打）—— 只报"哪一种不匹配"。
 */
@Slf4j
@Component
public class WorkerTokenVerifier {

    private final String workerToken;

    public WorkerTokenVerifier(@Value("${edam.worker.token:}") String workerToken) {
        this.workerToken = workerToken;
    }

    /**
     * 校验 Worker 回调令牌。
     *
     * @param provided 请求头 {@code X-Worker-Token} 的值
     * @return 通过返回 true
     */
    public boolean isValid(String provided) {
        if (workerToken == null || workerToken.isBlank() || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
            workerToken.getBytes(StandardCharsets.UTF_8),
            provided.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 说清楚"为什么拒"，而不是只丢一句 unauthorized。
     *
     * <p>三种拒绝原因分开报，运维一眼能看出该改哪一边：
     * <ul>
     *   <li>{@code backend_token_not_configured} —— 后端没配
     *       {@code edam.worker.token}（或 WORKER_TOKEN 环境变量为空）</li>
     *   <li>{@code worker_token_header_missing} —— worker 侧没发
     *       {@code X-Worker-Token} 头（多半是 worker 的 WORKER_TOKEN 没配）</li>
     *   <li>{@code token_mismatch} —— 两边都配了但值不一样</li>
     * </ul>
     */
    public String rejectReason(String provided) {
        if (workerToken == null || workerToken.isBlank()) {
            return "backend_token_not_configured";
        }
        if (provided == null || provided.isBlank()) {
            return "worker_token_header_missing";
        }
        return "token_mismatch";
    }
}
