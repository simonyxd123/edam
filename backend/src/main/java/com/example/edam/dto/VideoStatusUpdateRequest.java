package com.example.edam.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Worker 处理完视频后回调的 DTO（v3.2 V-2 视频处理）
 *
 * 用于 PATCH /videos/{video_id}/status
 *
 * 字段都是可选的：Worker 只想更新 HLS 或只想更新指纹都可以。
 */
@Data
public class VideoStatusUpdateRequest {

    /** HLS 状态：0=pending 1=processing 2=ready 3=failed */
    @JsonProperty("hls_status")
    private Integer hlsStatus;

    /** HLS 在 MinIO 上的目录（e.g. videos/1/hls/playlist.m3u8） */
    @JsonProperty("hls_path")
    private String hlsPath;

    /** 指纹状态：0=pending 1=processing 2=ready 3=failed */
    @JsonProperty("fingerprint_status")
    private Integer fingerprintStatus;

    /** 指纹 JSON 在 MinIO 上的路径 */
    @JsonProperty("fingerprint_path")
    private String fingerprintPath;

    /** 视频时长（秒，Worker 用 ffprobe 提取） */
    @JsonProperty("duration_sec")
    private Long durationSec;
}