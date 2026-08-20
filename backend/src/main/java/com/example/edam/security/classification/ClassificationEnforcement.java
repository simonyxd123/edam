package com.example.edam.security.classification;

import com.example.edam.model.DocResource;
import com.example.edam.model.VideoResource;
import com.example.edam.repository.DocResourceRepository;
import com.example.edam.repository.VideoResourceRepository;
import com.example.edam.security.SsoUserInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 数据分类强制打标中间件（v3.3 W-7.3）
 *
 * 在上传 Controller 调用前自动识别 + 强制打标
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClassificationEnforcement {

    private final DataClassificationService classificationService;
    private final VideoResourceRepository videoRepository;
    private final DocResourceRepository docRepository;

    /**
     * 视频上传时强制打标
     *
     * @param fileName       原始文件名
     * @param fileSize       文件大小
     * @param uploaderUserId 上传者 ID
     * @param uploaderDept   上传者部门
     * @param userProvided   用户手动选择（可空）
     * @return 强制打标的密级
     */
    @Transactional
    public ClassificationLevel enforceVideoClassification(
            String fileName,
            long fileSize,
            Long uploaderUserId,
            String uploaderDept,
            ClassificationLevel userProvided
    ) {
        ClassificationLevel level = classificationService.enforceClassification(
            fileName, fileSize, uploaderDept, userProvided, uploaderUserId);

        log.info("video_classification_enforced file={} level={} user={}",
            fileName, level.getCode(), uploaderUserId);
        return level;
    }

    /**
     * 文档上传时强制打标
     */
    @Transactional
    public ClassificationLevel enforceDocClassification(
            String fileName,
            long fileSize,
            Long uploaderUserId,
            String uploaderDept,
            ClassificationLevel userProvided
    ) {
        ClassificationLevel level = classificationService.enforceClassification(
            fileName, fileSize, uploaderDept, userProvided, uploaderUserId);

        log.info("doc_classification_enforced file={} level={} user={}",
            fileName, level.getCode(), uploaderUserId);
        return level;
    }

    /**
     * 上传完成后调用：写入分类到资源表 + 审计
     */
    @Transactional
    public void persistVideoClassification(
            Long videoId,
            ClassificationLevel newLevel,
            Long operatorId) {
        VideoResource video = videoRepository.selectById(videoId);
        if (video == null) return;

        ClassificationLevel oldLevel = video.getClassificationLv() != null
            ? ClassificationLevel.fromCode(toLevelCode(video.getClassificationLv()))
            : ClassificationLevel.L1_PUBLIC;

        video.setClassificationLv(parseLevel(newLevel));
        video.setUpdatedAt(LocalDateTime.now());
        videoRepository.updateById(video);

        classificationService.recordChange(
            "video", videoId,
            oldLevel, newLevel,
            "上传时自动分类",
            operatorId,
            "auto",
            null
        );
    }

    @Transactional
    public void persistDocClassification(
            Long docId,
            ClassificationLevel newLevel,
            Long operatorId) {
        DocResource doc = docRepository.selectById(docId);
        if (doc == null) return;

        ClassificationLevel oldLevel = doc.getClassificationLv() != null
            ? ClassificationLevel.fromCode(toLevelCode(doc.getClassificationLv()))
            : ClassificationLevel.L1_PUBLIC;

        doc.setClassificationLv(parseLevel(newLevel));
        docRepository.updateById(doc);

        classificationService.recordChange(
            "document", docId,
            oldLevel, newLevel,
            "上传时自动分类",
            operatorId,
            "auto",
            null
        );
    }

    /**
     * 人工变更密级
     */
    @Transactional
    public void changeClassification(
            String resourceType,
            Long resourceId,
            ClassificationLevel newLevel,
            Long operatorId,
            String reason) {
        ClassificationLevel oldLevel;
        if ("video".equals(resourceType)) {
            VideoResource video = videoRepository.selectById(resourceId);
            if (video == null) return;
            oldLevel = video.getClassificationLv() != null
                ? ClassificationLevel.fromCode(toLevelCode(video.getClassificationLv()))
                : ClassificationLevel.L1_PUBLIC;
            video.setClassificationLv(parseLevel(newLevel));
            video.setUpdatedAt(LocalDateTime.now());
            videoRepository.updateById(video);
        } else {
            DocResource doc = docRepository.selectById(resourceId);
            if (doc == null) return;
            oldLevel = doc.getClassificationLv() != null
                ? ClassificationLevel.fromCode(toLevelCode(doc.getClassificationLv()))
                : ClassificationLevel.L1_PUBLIC;
            doc.setClassificationLv(parseLevel(newLevel));
            docRepository.updateById(doc);
        }

        classificationService.recordChange(
            resourceType, resourceId,
            oldLevel, newLevel,
            reason,
            operatorId,
            "manual",
            null
        );

        log.warn("classification_manually_changed resource={}:{} {} -> {} reason={} operator={}",
            resourceType, resourceId,
            oldLevel.getCode(), newLevel.getCode(),
            reason, operatorId);
    }

    private Integer parseLevel(ClassificationLevel level) {
        return switch (level) {
            case L1_PUBLIC -> 1;
            case L2_INTERNAL -> 2;
            case L3_CONFIDENTIAL -> 3;
            case L4_TOP_SECRET -> 4;
        };
    }

    private String toLevelCode(Integer level) {
        return switch (level) {
            case 1 -> "L1";
            case 2 -> "L2";
            case 3 -> "L3";
            case 4 -> "L4";
            default -> "L1";
        };
    }
}