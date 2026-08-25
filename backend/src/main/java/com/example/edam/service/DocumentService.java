package com.example.edam.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edam.exception.ResourceNotFoundException;
import com.example.edam.model.DocResource;
import com.example.edam.repository.DocResourceRepository;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
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
 * 文档 Service
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocResourceRepository docRepository;
    private final RabbitTemplate rabbitTemplate;
    private final MinioClient minioClient;

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
        LambdaQueryWrapper<DocResource> wrapper = new LambdaQueryWrapper<>();
        if (fileType != null) {
            wrapper.eq(DocResource::getFileType, fileType);
        }
        wrapper.orderByDesc(DocResource::getUploadTime);
        return docRepository.selectPage(new Page<>(page, pageSize), wrapper);
    }

    /**
     * 上传文档
     */
    @Transactional
    public DocResource upload(MultipartFile file, String classificationLv, String title,
                              boolean enableWatermark, Long uploaderId) {
        try {
            // 1. 计算 hash
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(file.getBytes());
            String fileHash = HexFormat.of().formatHex(hashBytes);

            // 2. 秒传
            DocResource existing = docRepository.findByFileHash(fileHash);
            if (existing != null) {
                log.info("doc_dedup_hit, file_hash={}", fileHash);
                return existing;
            }

            // 3. 保存到 MinIO（先确保 bucket 存在）
            String originalName = file.getOriginalFilename();
            String fileType = detectFileType(originalName);
            String minioPath = String.format("uploads/%d/%s.%s",
                uploaderId, UUID.randomUUID(), fileType);

            ensureBucketExists(documentsBucket);
            uploadToMinio(documentsBucket, minioPath, file);

            // 4. 创建记录
            DocResource doc = new DocResource();
            doc.setTitle(title != null ? title : originalName);
            doc.setFileType(fileType);
            doc.setFileHash(fileHash);
            doc.setMinioPath(minioPath);
            doc.setPreviewPath(null);
            doc.setSizeBytes(file.getSize());
            doc.setMimeType(file.getContentType());
            doc.setClassificationLv(parseClassification(classificationLv));
            doc.setUploaderId(uploaderId);
            doc.setWatermarkStatus(enableWatermark ? 0 : 4);  // 0=pending 4=skipped
            doc.setPreviewStatus(0);
            doc.setEncrypted(1);
            docRepository.insert(doc);

            // 4.1 防御：INSERT 后立即 SELECT 验证持久化（防止事务回滚时返回假 ID）
            DocResource verify = docRepository.selectById(doc.getId());
            if (verify == null) {
                log.error("doc_insert_verify_failed, id={}", doc.getId());
                throw new RuntimeException("INSERT 未持久化，doc_id=" + doc.getId());
            }
            log.info("doc_insert_verified, id={}", doc.getId());

            // 5. 触发异步处理（水印 + 预览生成）
            rabbitTemplate.convertAndSend(exchange, documentRouting, java.util.Map.of(
                "doc_id", doc.getId(),
                "input_path", minioPath,
                "file_type", fileType,
                "classification_lv", classificationLv,
                "uploader_id", uploaderId,
                "enable_watermark", enableWatermark
            ));

            log.info("doc_uploaded, doc_id={}, file_type={}, size={}, minio={}",
                doc.getId(), fileType, file.getSize(), minioPath);
            return doc;

        } catch (Exception e) {
            log.error("doc_upload_failed", e);
            throw new RuntimeException("文档上传失败: " + e.getMessage(), e);
        }
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

    @Transactional
    public void delete(Long id, Long currentUserId) {
        DocResource doc = getById(id);
        if (!doc.getUploaderId().equals(currentUserId)) {
            log.warn("doc_delete_by_non_owner, doc_id={}, user_id={}", id, currentUserId);
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
}