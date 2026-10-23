package com.example.edam.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edam.exception.ResourceNotFoundException;
import com.example.edam.model.VideoResource;
import com.example.edam.model.SysUser;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.repository.VideoResourceRepository;
import com.example.edam.util.FileHashUtils;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;
import com.example.edam.security.CurrentUser;
import org.springframework.security.access.AccessDeniedException;

/**
 * 视频资源 Service
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoService {

    private final VideoResourceRepository videoRepository;
    private final SysUserRepository sysUserRepository;
    private final RabbitTemplate rabbitTemplate;
    private final MinioClient minioClient;
    /** 用于在方法内部精确划定事务边界（见 upload 的三段式说明） */
    private final PlatformTransactionManager txManager;

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
        return list(page, pageSize, classificationLv, uploaderId, null);
    }

    /**
     * 视频列表（分页 + 过滤 + 标题关键字）
     *
     * @param keyword 标题模糊匹配关键字；null / 空 = 不过滤
     */
    public Page<VideoResource> list(int page, int pageSize, String classificationLv,
                                    Long uploaderId, String keyword) {
        LambdaQueryWrapper<VideoResource> wrapper = new LambdaQueryWrapper<>();
        if (classificationLv != null) {
            wrapper.eq(VideoResource::getClassificationLv, parseClassification(classificationLv));
        }
        if (uploaderId != null) {
            wrapper.eq(VideoResource::getUploaderId, uploaderId);
        }
        if (keyword != null && !keyword.isBlank()) {
            // 关键字过滤：此前搜索端点收了 q 却**完全不用**，
            // 返回全量列表 —— 用户搜什么都能搜到全部
            wrapper.and(w -> w.like(VideoResource::getTitle, keyword.trim())
                              .or().like(VideoResource::getMinioPath, keyword.trim()));
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
        // 多取 1 个用于判断 has_more。
        //
        // 原来写成 wrapper.last("LIMIT " + (limit + 1)) —— 把 int 拼进原生 SQL。
        // 当前调用方（VideoListController）已经把 limit 夹在 1~100，所以暂时
        // 不可注入，但这是"一旦上层漏掉夹取就变成注入"的形状。
        //
        // MyBatis-Plus 3.5.7 的 last() 只有 last(boolean, String) 重载，
        // **不支持参数化**，所以换用 Page 对象：selectPage 会生成参数化的
        // `LIMIT ?, ?`（偏移固定 0，等价于 `LIMIT size`）。
        // searchCount 关掉 —— cursor 分页不需要总数，多一次 count 纯属浪费。
        Page<VideoResource> page = new Page<>(1, limit + 1);
        page.setSearchCount(false);
        java.util.List<VideoResource> result = videoRepository.selectPage(page, wrapper).getRecords();
        if (result.size() > limit) {
            return result.subList(0, limit);
        }
        return result;
    }

    /**
     * 上传视频
     *
     * 刻意不加 {@code @Transactional}：整个流程里最慢的是 MinIO 上传，
     * 8GB 文件可能十几分钟，放进事务会一直占着 Hikari 连接和事务，
     * 并发几笔就能把连接池（20~50，connection-timeout 仅 30s）打满，
     * 把列表、鉴权这些普通接口一起拖死。
     *
     * 改为三段：hash（无事务）→ 秒传查询（短只读事务）→
     * MinIO put（无事务）→ 落库+发MQ（短事务）。
     * 落库与发 MQ 仍放在同一个短事务里，保住「有记录必有消息」的一致性。
     */
    public VideoResource upload(MultipartFile file, String classificationLv, String title, Long uploaderId) {
        try {
            // 1. 计算文件 hash（流式，避免大文件整个进堆）
            String fileHash = FileHashUtils.sha256Hex(file);

            // 2. 检查秒传（短只读事务）
            VideoResource existing = findExistingForDedup(fileHash);
            if (existing != null) {
                log.info("video_dedup_hit, file_hash={}", fileHash);
                return existing;
            }

            // 3. 真实上传到 MinIO（先确保 bucket 存在）—— 不占事务、不占数据库连接
            //    扩展名必须来自**服务端白名单**，不能用客户端的 Content-Type：
            //    Content-Type: video/../../../../other-bucket/secret 会让扩展名
            //    变成 "../../../other-bucket/secret"，最终 key 形如
            //    uploads/1/<uuid>.../../other-bucket/secret —— 目录穿越。
            String minioPath = String.format("uploads/%d/%s.%s",
                uploaderId, UUID.randomUUID(), detectVideoExtension(file));

            ensureBucketExists(videosBucket);
            uploadToMinio(videosBucket, minioPath, file);

            // 4. 查 employee_no 让 Worker 把工号烧进 HLS 水印（事务外先查好）
            String employeeNo = lookupEmployeeNo(uploaderId);
            if (employeeNo == null || employeeNo.isBlank()) {
                employeeNo = "U" + uploaderId;  // 查不到时降级
            }

            // 5. 落库 + 触发异步处理（短事务，毫秒级）
            VideoResource video = persistAndPublish(classificationLv, title, uploaderId, fileHash, minioPath, employeeNo, file);

            log.info("video_uploaded, video_id={}, size={}, minio={}",
                video.getId(), file.getSize(), minioPath);
            return video;
        } catch (Exception e) {
            log.error("video_upload_failed", e);
            throw new RuntimeException("视频上传失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 Content-Type 推断视频扩展名 —— **只认白名单里的 mime 子类型**。
     *
     * <p>不能直接取 {@code contentType.substring(indexOf('/') + 1)}：
     * 客户端可以传 {@code video/../../../../secret}，那样扩展名会变成
     * {@code ../../../secret}，拼进 MinIO object name 就是目录穿越。
     */
    private String detectVideoExtension(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null) {
            String subtype = contentType.toLowerCase().trim();
            int slash = subtype.indexOf('/');
            if (slash >= 0) {
                subtype = subtype.substring(slash + 1);
            }
            // 去掉可能的参数（video/mp4; charset=...）
            int semi = subtype.indexOf(';');
            if (semi >= 0) {
                subtype = subtype.substring(0, semi).trim();
            }
            switch (subtype) {
                case "mp4":
                case "mpeg":
                case "quicktime":
                case "x-m4v":
                case "x-msvideo":
                case "webm":
                case "x-matroska":
                case "3gpp":
                    return subtype.replace("x-", "");
                default:
                    break;
            }
        }
        // Content-Type 不可信 → 退回按原始文件名判断
        String name = file.getOriginalFilename();
        if (name != null) {
            String lower = name.toLowerCase();
            if (lower.endsWith(".mp4")) return "mp4";
            if (lower.endsWith(".mov")) return "mov";
            if (lower.endsWith(".avi")) return "avi";
            if (lower.endsWith(".mkv")) return "mkv";
            if (lower.endsWith(".webm")) return "webm";
            if (lower.endsWith(".m4v")) return "m4v";
            if (lower.endsWith(".mpeg") || lower.endsWith(".mpg")) return "mpeg";
        }
        return "mp4";
    }

    /**
     * 秒传查询：短只读事务。
     */
    private VideoResource findExistingForDedup(String fileHash) {        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setReadOnly(true);
        return tx.execute(s -> videoRepository.findByFileHash(fileHash));
    }

    /**
     * 落库 + 发 MQ：短事务。
     *
     * RabbitTemplate 的 convertAndSend 默认是异步投递且不等待 confirm，
     * 在这里执行几乎不耗时，因此可以和 INSERT 放在同一事务里 ——
     * MQ 投递失败则整笔回滚，避免留下「有记录但永远不会被转码」的视频。
     */
    private VideoResource persistAndPublish(String classificationLv, String title, Long uploaderId,
                                            String fileHash, String minioPath, String employeeNo,
                                            MultipartFile file) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        return tx.execute(s -> {
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

            // 防御：INSERT 后立即 SELECT 验证（防止事务回滚时前端拿到 ID 但 DB 没记录）
            VideoResource verify = videoRepository.selectById(video.getId());
            if (verify == null) {
                log.error("video_insert_verify_failed, id={}", video.getId());
                throw new RuntimeException("INSERT 未持久化，video_id=" + video.getId());
            }
            log.info("video_insert_verified, id={}", video.getId());

            // 触发异步处理（HLS 转码 + 帧指纹）
            rabbitTemplate.convertAndSend(exchange, videoRouting, java.util.Map.of(
                "video_id", video.getId(),
                "input_path", minioPath,
                "classification_lv", classificationLv,
                "uploader_id", uploaderId,
                "employee_no", employeeNo
            ));
            return video;
        });
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
            // partSize = -1 → 由 SDK 自动决定分段大小。
            //
            // 注意阈值：S3 协议单次 PUT 上限 5 GiB，超过必须走分段上传。
            // 上限从 2GB 提到 8GB 后，这里会从「单次 PUT」自动切成「分段上传」
            // （8 GiB / 最小 5 MiB ≈ 1638 段，远低于 10000 段上限）。
            //
            // 刻意保持 -1 不改成显式分段大小：显式 partSize 会触发 SDK 对
            // 「输入流必须可 reset」的校验，而 MultipartFile 的流来自 Tomcat
            // 临时文件，reset 行为随容器版本而异，反而可能把现在能跑的
            // 路径弄坏。交给 SDK 自动决策更稳。
            //
            // ⚠️ 8GB 实际分段上传尚未做过端到端冒烟测试，见 modify/ 变更记录。
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
     *
     * <p>归属校验：只有上传者本人或管理员能删。
     * 原实现对非 owner 只打一行 warn 就照样删 —— 任何持有
     * {@code video:delete} 权限的普通用户都能删掉别人的视频（IDOR）。
     */
    @Transactional
    public void delete(Long id, Long currentUserId) {
        VideoResource video = getById(id);
        if (!video.getUploaderId().equals(currentUserId) && !CurrentUser.isAdmin()) {
            log.warn("video_delete_by_non_owner_rejected, video_id={}, user_id={}", id, currentUserId);
            throw new AccessDeniedException("无权删除他人上传的视频: " + id);
        }
        videoRepository.deleteById(id);
        log.info("video_deleted, video_id={}, operator_id={}", id, currentUserId);
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
     * 用 userId 查 employee_no（WatermarkService 同样逻辑）
     */
    private String lookupEmployeeNo(Long userId) {
        if (userId == null) return null;
        try {
            SysUser u = sysUserRepository.selectById(userId);
            return u != null ? u.getEmployeeNo() : null;
        } catch (Exception e) {
            log.warn("employee_no_lookup_failed, userId={}", userId);
            return null;
        }
    }

    /**
     * 更新处理状态（Worker 处理完 HLS / 指纹后回调）
     *
     * @param videoId           视频 ID
     * @param hlsStatus         HLS 状态：0=pending 1=processing 2=ready 3=failed
     * @param hlsPath           HLS 目录在 MinIO 上的路径（e.g. videos/1/hls/playlist.m3u8）
     * @param fingerprintStatus 指纹状态：0=pending 1=processing 2=ready 3=failed
     * @param fingerprintPath   指纹 JSON 在 MinIO 上的路径
     * @param durationSec       视频时长（秒，Worker 用 ffprobe 提取）
     */
    @Transactional
    public void updateProcessingStatus(Long videoId,
                                       Integer hlsStatus, String hlsPath,
                                       Integer fingerprintStatus, String fingerprintPath,
                                       Long durationSec) {
        VideoResource v = getById(videoId);
        if (hlsStatus != null) v.setHlsStatus(hlsStatus);
        if (hlsPath != null) v.setHlsPath(hlsPath);
        if (fingerprintStatus != null) v.setFingerprintStatus(fingerprintStatus);
        if (fingerprintPath != null) v.setFingerprintPath(fingerprintPath);
        if (durationSec != null) v.setDurationSec(durationSec);
        videoRepository.updateById(v);
        log.info("video_processing_status_updated, video_id={}, hls={}, fp={}, duration={}s",
            videoId, hlsStatus, fingerprintStatus, durationSec);
    }
}