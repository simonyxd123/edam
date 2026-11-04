package com.example.edam.service;

import com.example.edam.model.PlayLog;
import com.example.edam.repository.PlayLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 播放日志 Service（play_log）。
 *
 * <p>方案书 §7.1：播放日志表(play_log)；
 * §7.3：播放日志保留 365 天（合规要求）。
 *
 * <p><b>与 operation_log 的分工</b>：
 * <ul>
 *   <li>{@code operation_log}：谁做了什么操作（登录/查看/下载/授权…）</li>
 *   <li>{@code play_log}：谁把哪个视频看了多久、看到哪儿、有没有水印</li>
 * </ul>
 * 后者是**溯源**链的最后一环 —— 视频被外泄时，
 * 能拿出一条"某工号在 2026-xx-xx 14:32 用 30 分钟看完了内部培训视频"的记录。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlayLogService {

    private final PlayLogRepository playLogRepository;

    /**
     * 记一条播放事件。
     *
     * <p><b>异步写</b>：播放事件是高频的（一次播放好几条），
     * 不能拖慢播放器的心跳请求。队列满时由调用线程自己写 ——
     * 宁可慢一点，也不能丢（丢了就断不了溯源链）。
     *
     * <p><b>写失败不抛</b>：播放日志写不进去不该让前端的心跳请求报错。
     */
    @Async("auditExecutor")
    public void record(Long userId, Long videoId, String sessionId,
                       String event, Integer progressSec,
                       String ip, String userAgent,
                       Boolean watermarkApplied) {
        try {
            PlayLog entry = new PlayLog();
            entry.setUserId(userId);
            entry.setVideoId(videoId);
            entry.setSessionId(sessionId);
            entry.setEvent(event);
            entry.setProgressSec(progressSec == null ? 0 : progressSec);
            entry.setIp(ip);
            entry.setUserAgent(userAgent);
            entry.setWatermarkApplied(watermarkApplied == null || watermarkApplied ? 1 : 0);
            entry.setFingerprintExtracted(0);
            playLogRepository.insert(entry);
        } catch (Exception e) {
            log.error("play_log_write_failed, user_id={}, video_id={}, event={}", userId, videoId, event, e);
        }
    }
}
