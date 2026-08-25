package com.example.edam.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edam.exception.ResourceNotFoundException;
import com.example.edam.model.VideoResource;
import com.example.edam.repository.VideoResourceRepository;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 视频资源 Service
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoService {

    private final VideoResourceRepository videoRepository;
    private final RabbitTemplate rabbitTemplate;
    private final MinioClient minioClient;

    @Value("${edam.rabbitmq.exchange:edam.tasks}")
    private String exchange;

    @Value("${edam.rabbitmq.routing.video:video.preprocess}")
    private String videoRouting;

    @Value("${minio.bucket.videos}")
    private String videosBucket;

    /**
     * 视频列表（分页 + 过滤）
     */
    public Page<VideoResource> list(int page, int pageSize, String classificationLv, Long uploaderId) {
        LambdaQueryWrapper<VideoResource> wrapper = new LambdaQueryWrapper<>();
        if (classificationLv != null) {
            wrapper.eq(VideoResource::getClassificationLv, parseClassification(classificationLv));
        }
        if (uploaderId != null) {
            wrapper.eq(VideoResource::getUploaderId, uploaderId);
        }
        wrapper.orderByDesc(VideoResource::getUploadTime);
        return videoRepository.selectPage(new Page<>(page, pageSize), wrapper);
    }

    /**
     * Cursor 分页列表（v3.2 V-4）
     *
     * 基于 id < cursor.id 的倒序查询，避免 OFFSET 性能衰减
     *
     * @param parts    cursor 解码结果（含 id + ts）
     * @param limit    每页数量（1-100）
     * @param classificationLv 密级过滤
     * @param uploaderId     上传者过滤
     */
    public java.util.List<VideoResource> listByCursor(
            com.example.edam.util.CursorUtil.CursorParts parts,
            int limit, String classificationLv, Long uploaderId) {
        LambdaQueryWrapper<VideoResource> wrapper = new LambdaQueryWrapper<>();
        if (parts != null) {
            // 倒序分页：id < cursor.id
            wrapper.lt(VideoResource::getId, parts.id());
        }
        if (classificationLv != null) {
            wrapper.eq(VideoResource::getClassificationLv, parseClassification(classificationLv));
        }
        if (uploaderId != null) {
            wrapper.eq(VideoResource::getUploaderId, uploaderId);
        }
        wrapper.orderByDesc(VideoResource::getId);
        // 多取 1 个用于判断 has_more
        wrapper.last("LIMIT " + (limit + 1));
        java.util.List<VideoResource> result = videoRepository.selectList(wrapper);
        if (result.size() > limit) {
            return result.subList(0, limit);
        }
        return result;
    }

    /**
     * 上传视频
     */
    @Transactional
    public VideoResource upload(MultipartFile file, String classificationLv, String title, Long uploaderId) {
        try {
            // 1. 计算文件 hash
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(file.getBytes());
            String fileHash = HexFormat.of().formatHex(hashBytes);

            // 2. 检查秒传
            VideoResource existing = videoRepository.findByFileHash(fileHash);
            if (existing != null) {
                log.info("video_dedup_hit, file_hash={}", fileHash);
                return existing;
            }

            // 3. 真实上传到 MinIO（先确保 bucket 存在）
            String minioPath = String.format("uploads/%d/%s.%s",
                uploaderId, UUID.randomUUID(),
                file.getContentType() != null && file.getContentType().contains("/")
                    ? file.getContentType().substring(file.getContentType().indexOf('/') + 1)
                    : "mp4");

            ensureBucketExists(videosBucket);
            uploadToMinio(videosBucket, minioPath, file);

            // 4. 创建数据库记录
            VideoResource video = new VideoResource();
            video.setTitle(title != null ? title : file.getOriginalFilename());
            video.setFileHash(fileHash);
            video.setMinioPath(minioPath);
            video.setSizeBytes(file.getSize());
            video.setMimeType(file.getContentType());
            video.setClassificationLv(parseClassification(classificationLv));
            video.setUploaderId(uploaderId);
            video.setHlsStatus(0);  // pending
            video.setFingerprintStatus(0);
            videoRepository.insert(video);

            // 5. 触发异步处理（HLS 转码 + 帧指纹）
            rabbitTemplate.convertAndSend(exchange, videoRouting, java.util.Map.of(
                "video_id", video.getId(),
                "input_path", minioPath,
                "classification_lv", classificationLv,
                "uploader_id", uploaderId
            ));

            log.info("video_uploaded, video_id={}, size={}, minio={}",
                video.getId(), file.getSize(), minioPath);
            return video;
        } catch (Exception e) {
            log.error("video_upload_failed", e);
            throw new RuntimeException("视频上传失败: " + e.getMessage(), e);
        }
    }

    /**
     * 确保 MinIO bucket 存在（不存在则创建）
     */
    private void ensureBucketExists(String bucket) {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("minio_bucket_created, bucket={}", bucket);
            }
        } catch (Exception e) {
            log.error("minio_bucket_check_failed, bucket={}", bucket, e);
            throw new RuntimeException("MinIO bucket 检查/创建失败: " + e.getMessage(), e);
        }
    }

    /**
     * 上传文件到 MinIO
     */
    private void uploadToMinio(String bucket, String objectName, MultipartFile file) throws Exception {
        try (InputStream is = file.getInputStream()) {
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(is, file.getSize(), -1)
                    .contentType(file.getContentType() != null ? file.getContentType() : "application/octet-stream")
                    .build()
            );
            log.info("minio_upload_ok, bucket={}, object={}, size={}", bucket, objectName, file.getSize());
        } catch (Exception e) {
            log.error("minio_upload_failed, bucket={}, object={}", bucket, objectName, e);
            throw  e;
        }
    }

    /**
     * 视频详情
     */
    public VideoResource getById(Long id) {
        VideoResource video = videoRepository.selectById(id);
        if (video == null || video.getDeletedAt() != null) {
            throw new ResourceNotFoundException("视频不存在: " + id);
        }
        return video;
    }

    /**
     * 删除视频
     */
    @Transactional
    public void delete(Long id, Long currentUserId) {
        VideoResource video = getById(id);
        if (!video.getUploaderId().equals(currentUserId)) {
            // 实际应该检查 admin 权限
            log.warn("video_delete_by_non_owner, video_id={}, user_id={}", id, currentUserId);
        }
        videoRepository.deleteById(id);
        log.info("video_deleted, video_id={}", id);
    }

    private Integer parseClassification(String lv) {
        if (lv == null) return 1;
        return switch (lv.toUpperCase()) {
            case "L1" -> 1;
            case "L2" -> 2;
            case "L3" -> 3;
            case "L4" -> 4;
            default -> 1;
        };
    }

    /**
     * 更新处理状态（Worker 处理完 HLS / 指纹后回调）
     *
     * @param videoId           视频 ID
     * @param hlsStatus         HLS 状态：0=pending 1=processing 2=ready 3=failed
     * @param hlsPath           HLS 目录在 MinIO 上的路径（e.g. videos/1/hls/playlist.m3u8）
     * @param fingerprintStatus 指纹状态：0=pending 1=processing 2=ready 3=failed
     * @param fingerprintPath   指纹 JSON 在 MinIO 上的路径
     */
    @Transactional
    public void updateProcessingStatus(Long videoId,
                                       Integer hlsStatus, String hlsPath,
                                       Integer fingerprintStatus, String fingerprintPath) {
        VideoResource v = getById(videoId);
        if (hlsStatus != null) v.setHlsStatus(hlsStatus);
        if (hlsPath != null) v.setHlsPath(hlsPath);
        if (fingerprintStatus != null) v.setFingerprintStatus(fingerprintStatus);
        if (fingerprintPath != null) v.setFingerprintPath(fingerprintPath);
        videoRepository.updateById(v);
        log.info("video_processing_status_updated, video_id={}, hls={}, fp={}",
            videoId, hlsStatus, fingerprintStatus);
    }
}