package com.example.edam.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edam.exception.ResourceNotFoundException;
import com.example.edam.model.DocResource;
import com.example.edam.repository.DocResourceRepository;
import com.example.edam.security.classification.ClassificationEnforcement;
import com.example.edam.security.classification.ClassificationLevel;
import com.example.edam.util.FileHashUtils;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
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
 * 文档 Service
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocResourceRepository docRepository;
    private final DocPermissionService docPermissionService;
    private final RabbitTemplate rabbitTemplate;
    private final MinioClient minioClient;
    private final ClassificationEnforcement classificationEnforcement;
    /** 用于在方法内部精确划定事务边界（见 upload 的说明） */
    private final PlatformTransactionManager txManager;

    @Value("${edam.rabbitmq.exchange:edam.tasks}")
    private String exchange;

    @Value("${edam.rabbitmq.routing.document:document.preprocess}")
    private String documentRouting;

    @Value("${minio.bucket.documents}")
    private String documentsBucket;

    /**
     * 文档列表
     */
    public Page<DocResource> list(int page, int pageSize, String classificationLv, String fileType) {
        return list(page, pageSize, classificationLv, fileType, null);
    }

    /**
     * 文档列表（分页 + 过滤 + 标题关键字）
     *
     * @param keyword 标题模糊匹配关键字；null / 空 = 不过滤
     */
    public Page<DocResource> list(int page, int pageSize, String classificationLv,
                                   String fileType, String keyword) {
        LambdaQueryWrapper<DocResource> wrapper = new LambdaQueryWrapper<>();
        if (fileType != null) {
            wrapper.eq(DocResource::getFileType, fileType);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(DocResource::getTitle, keyword.trim());
        }
        // 密级过滤：原来收了 classification_lv 参数却**完全不用** ——
        // 前端「密级」下拉框选了等于没选
        Integer lvFilter = parseClassification(classificationLv);
        if (lvFilter != null) {
            wrapper.eq(DocResource::getClassificationLv, lvFilter);
        }
        wrapper.orderByDesc(DocResource::getUploadTime);
        // 可见性过滤（密级 + 显式授权）—— 与 DocPermissionService.canView 同源，
        // 与视频侧（VideoService.list）保持一致的做法。
        docPermissionService.applyVisibility(wrapper, CurrentUser.optionalUserId());
        return docRepository.selectPage(new Page<>(page, pageSize), wrapper);
    }

    /**
     * 上传文档
     *
     * 与 VideoService.upload 同样刻意不加 {@code @Transactional}：
     * MinIO 上传是唯一耗时步骤，移出事务才不会长时间占住数据库连接。
     * 事务边界用 TransactionTemplate 显式划定，落库与发 MQ 仍在同一短事务内。
     */
    public DocResource upload(MultipartFile file, String classificationLv, String title,
                              boolean enableWatermark, Long uploaderId) {
        try {
            // 1. 计算 hash（流式，避免大文件整个进堆）
            String fileHash = FileHashUtils.sha256Hex(file);

            // 2. 秒传（短只读事务）
            DocResource existing = findExistingForDedup(fileHash);
            if (existing != null) {
                log.info("doc_dedup_hit, file_hash={}", fileHash);
                return existing;
            }

            // 3. 保存到 MinIO（先确保 bucket 存在）—— 不占事务、不占数据库连接
            String originalName = file.getOriginalFilename();
            String fileType = detectFileType(originalName);
            String minioPath = String.format("uploads/%d/%s.%s",
                uploaderId, UUID.randomUUID(), fileType);

            ensureBucketExists(documentsBucket);
            uploadToMinio(documentsBucket, minioPath, file);

            // 4. 密级裁决（服务端）—— 客户端只能往严里选，不能往松里选
            ClassificationEnforcement.ClassificationDecision classification =
                classificationEnforcement.resolveUploadLevel(
                    originalName, file.getSize(), uploaderId, classificationLv);

            // 5. 落库 + 触发异步处理（短事务，毫秒级）
            DocResource doc = persistAndPublish(classification, title, uploaderId,
                    fileHash, minioPath, fileType, originalName, enableWatermark, file);

            log.info("doc_uploaded, doc_id={}, file_type={}, size={}, minio={}",
                doc.getId(), fileType, file.getSize(), minioPath);
            return doc;

        } catch (IllegalArgumentException e) {
            // 参数非法（如密级取值非法）→ 400，不要包成 500
            throw e;
        } catch (Exception e) {
            log.error("doc_upload_failed", e);
            throw new RuntimeException("文档上传失败: " + e.getMessage(), e);
        }
    }

    /**
     * 秒传查询：短只读事务
     */
    private DocResource findExistingForDedup(String fileHash) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setReadOnly(true);
        return tx.execute(s -> docRepository.findByFileHash(fileHash));
    }

    /**
     * 落库 + 发 MQ：短事务。
     * MQ 投递失败会连同 INSERT 一起回滚，避免留下永远不会被处理的记录。
     */
    private DocResource persistAndPublish(ClassificationEnforcement.ClassificationDecision classification,
                                          String title, Long uploaderId,
                                          String fileHash, String minioPath, String fileType,
                                          String originalName, boolean enableWatermark,
                                          MultipartFile file) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        return tx.execute(s -> {
            DocResource doc = new DocResource();
            doc.setTitle(title != null ? title : originalName);
            doc.setFileType(fileType);
            doc.setFileHash(fileHash);
            doc.setMinioPath(minioPath);
            doc.setPreviewPath(null);
            doc.setSizeBytes(file.getSize());
            doc.setMimeType(file.getContentType());
            // 落库用的是**裁决后**的密级，不是客户端声明的那个
            doc.setClassificationLv(classification.getFinalLevel().getLevel());
            doc.setUploaderId(uploaderId);
            doc.setWatermarkStatus(enableWatermark ? 0 : 4);  // 0=pending 4=skipped
            doc.setPreviewStatus(0);
            doc.setEncrypted(1);
            docRepository.insert(doc);

            // 防御：INSERT 后立即 SELECT 验证持久化（防止事务回滚时返回假 ID）
            DocResource verify = docRepository.selectById(doc.getId());
            if (verify == null) {
                log.error("doc_insert_verify_failed, id={}", doc.getId());
                throw new RuntimeException("INSERT 未持久化，doc_id=" + doc.getId());
            }
            log.info("doc_insert_verified, id={}", doc.getId());

            // 触发异步处理（水印 + 预览生成）
            rabbitTemplate.convertAndSend(exchange, documentRouting, java.util.Map.of(
                "doc_id", doc.getId(),
                "input_path", minioPath,
                "file_type", fileType,
                // MQ 里也带裁决后的密级
                "classification_lv", classification.getFinalLevel().getCode(),
                "uploader_id", uploaderId,
                "enable_watermark", enableWatermark
            ));

            // 密级裁决留痕（data_classification_audit）
            classificationEnforcement.recordUploadDecision(
                "document", doc.getId(), classification, uploaderId);

            return doc;
        });
    }

    /**
     * 确保 MinIO bucket 存在
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
            throw new RuntimeException("MinIO bucket 检查失败: " + e.getMessage(), e);
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
            throw e;
        }
    }

    public DocResource getById(Long id) {
        DocResource doc = docRepository.selectById(id);
        if (doc == null || doc.getDeletedAt() != null) {
            throw new ResourceNotFoundException("文档不存在: " + id);
        }
        return doc;
    }

    /**
     * 删除文档
     *
     * <p>归属校验：只有上传者本人或管理员能删。
     * 原实现对非 owner 只打 warn 就照样删（IDOR）。
     */
    @Transactional
    public void delete(Long id, Long currentUserId) {
        DocResource doc = getById(id);
        if (!doc.getUploaderId().equals(currentUserId) && !CurrentUser.isAdmin()) {
            log.warn("doc_delete_by_non_owner_rejected, doc_id={}, user_id={}", id, currentUserId);
            throw new AccessDeniedException("无权删除他人上传的文档: " + id);
        }
        docRepository.deleteById(id);
    }

    private String detectFileType(String filename) {
        if (filename == null) return "docx";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) return "xlsx";
        if (lower.endsWith(".pptx") || lower.endsWith(".ppt")) return "pptx";
        if (lower.endsWith(".png") || lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") || lower.endsWith(".gif") ||
            lower.endsWith(".bmp")) return "image";
        return "docx";
    }

    /**
     * 解析密级过滤值。
     *
     * <p><b>非法值不再静默降级成 L1</b> —— 原实现 {@code default -> 1} 意味着
     * 过滤条件传 {@code "L9"} 会变成"只看公开资产"，传空串也一样。
     * 现在：空 = 不过滤；非法 = 400。
     *
     * @return 密级数字（1-4）；null 表示不过滤
     */
    private Integer parseClassification(String lv) {
        if (lv == null || lv.isBlank()) return null;
        ClassificationLevel level = ClassificationLevel.fromCodeOrNull(lv);
        if (level == null) {
            throw new IllegalArgumentException(
                "密级取值非法: '" + lv + "'，合法取值为 L1 / L2 / L3 / L4");
        }
        return level.getLevel();
    }
}