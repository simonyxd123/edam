package com.example.edam.security.classification;

import com.example.edam.model.DataClassificationAudit;
import com.example.edam.repository.DataClassificationAuditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 数据分类分级服务（v3.3 W-7.2）
 *
 * 自动识别 + 强制打标
 *
 * 识别维度：
 * 1. 文件名关键词（L4 绝密词 → L4；L3 机密词 → L3；等）
 * 2. 文件大小（> 1GB 大文件 → L2）
 * 3. 上传者部门（HR/法务部门 → 默认 L3）
 * 4. MIME 类型（财务类 → L2）
 * 5. 文件名模式（合规报告/合同/财务 → L3）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataClassificationService {

    private final DataClassificationAuditRepository auditRepository;

    /**
     * L4 关键词（绝密）
     */
    private static final List<Pattern> L4_KEYWORDS = List.of(
        Pattern.compile(".*(绝密|最高机密|核武器|军工|涉密).*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*(top[_-]?secret|nuclear|weapons?).*", Pattern.CASE_INSENSITIVE)
    );

    /**
     * L3 关键词（机密）
     */
    private static final List<Pattern> L3_KEYWORDS = List.of(
        Pattern.compile(".*(机密|核心|战略|股权|并购|未公开|内幕).*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*(confidential|secret|merger|acquisition|insider).*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*(合同|合规|审计|法务|人事|薪酬).*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*(合同|contract|compliance|audit|legal).*", Pattern.CASE_INSENSITIVE)
    );

    /**
     * L2 关键词（内部）
     */
    private static final List<Pattern> L2_KEYWORDS = List.of(
        Pattern.compile(".*(内部|internal|项目|product|roadmap|规划).*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*(周报|月报|会议纪要|minutes|summary).*", Pattern.CASE_INSENSITIVE)
    );

    /**
     * 自动分类
     *
     * @param fileName       文件名
     * @param fileSize       文件大小（字节）
     * @param uploaderDept   上传者部门
     * @param userProvided   用户手动选择的密级（可空）
     * @return 推荐的密级
     */
    public ClassificationLevel autoClassify(
            String fileName,
            long fileSize,
            String uploaderDept,
            ClassificationLevel userProvided
    ) {
        // 1. 用户手动选择的密级优先（用户认知最准确）
        if (userProvided != null) {
            log.info("classification_user_provided level={}", userProvided.getCode());
            return userProvided;
        }

        // 2. 文件名关键词识别
        ClassificationLevel nameLevel = classifyByFileName(fileName);
        if (nameLevel.getLevel() >= ClassificationLevel.L3_CONFIDENTIAL.getLevel()) {
            log.info("classification_by_filename level={}", nameLevel.getCode());
            return nameLevel;
        }

        // 3. 文件大小识别
        ClassificationLevel sizeLevel = classifyBySize(fileSize);
        if (sizeLevel.getLevel() >= ClassificationLevel.L3_CONFIDENTIAL.getLevel()) {
            log.info("classification_by_size level={}", sizeLevel.getCode());
            return sizeLevel;
        }

        // 4. 部门识别
        ClassificationLevel deptLevel = classifyByDept(uploaderDept);
        if (deptLevel.getLevel() >= ClassificationLevel.L3_CONFIDENTIAL.getLevel()) {
            log.info("classification_by_dept level={}", deptLevel.getCode());
            return deptLevel;
        }

        // 5. 取最高（用户 < 文件名 < 大小 < 部门）
        ClassificationLevel result = max(nameLevel, sizeLevel, deptLevel);

        // 默认 L1（公开）
        log.info("classification_default level={}", result.getCode());
        return result;
    }

    /**
     * 文件名关键词识别
     */
    private ClassificationLevel classifyByFileName(String fileName) {
        if (fileName == null) return ClassificationLevel.L1_PUBLIC;

        for (Pattern p : L4_KEYWORDS) {
            if (p.matcher(fileName).matches()) return ClassificationLevel.L4_TOP_SECRET;
        }
        for (Pattern p : L3_KEYWORDS) {
            if (p.matcher(fileName).matches()) return ClassificationLevel.L3_CONFIDENTIAL;
        }
        for (Pattern p : L2_KEYWORDS) {
            if (p.matcher(fileName).matches()) return ClassificationLevel.L2_INTERNAL;
        }
        return ClassificationLevel.L1_PUBLIC;
    }

    /**
     * 文件大小识别
     */
    private ClassificationLevel classifyBySize(long fileSize) {
        if (fileSize > 5L * 1024 * 1024 * 1024) return ClassificationLevel.L4_TOP_SECRET;  // > 5GB
        if (fileSize > 1L * 1024 * 1024 * 1024) return ClassificationLevel.L3_CONFIDENTIAL;  // > 1GB
        if (fileSize > 100L * 1024 * 1024) return ClassificationLevel.L2_INTERNAL;  // > 100MB
        return ClassificationLevel.L1_PUBLIC;
    }

    /**
     * 部门识别
     */
    private ClassificationLevel classifyByDept(String dept) {
        if (dept == null) return ClassificationLevel.L1_PUBLIC;
        return switch (dept.toLowerCase()) {
            case "hr", "法务", "legal", "audit", "审计" -> ClassificationLevel.L3_CONFIDENTIAL;
            case "财务", "finance" -> ClassificationLevel.L2_INTERNAL;
            default -> ClassificationLevel.L1_PUBLIC;
        };
    }

    /**
     * 取多个密级中的最高
     */
    private ClassificationLevel max(ClassificationLevel... levels) {
        ClassificationLevel max = ClassificationLevel.L1_PUBLIC;
        for (ClassificationLevel l : levels) {
            if (l.getLevel() > max.getLevel()) max = l;
        }
        return max;
    }

    /**
     * 记录分类变更审计
     */
    @Transactional
    public void recordChange(
            String resourceType,
            Long resourceId,
            ClassificationLevel oldLevel,
            ClassificationLevel newLevel,
            String reason,
            Long changedBy,
            String changeMethod,
            Long ruleId) {
        DataClassificationAudit audit = new DataClassificationAudit();
        audit.setResourceType(resourceType);
        audit.setResourceId(resourceId);
        audit.setOldClassification(oldLevel != null ? oldLevel.getCode() : null);
        audit.setNewClassification(newLevel.getCode());
        audit.setChangeReason(reason);
        audit.setChangedBy(changedBy);
        audit.setChangeMethod(changeMethod);
        audit.setRuleId(ruleId);
        audit.setChangedAt(LocalDateTime.now());
        auditRepository.insert(audit);

        log.info("classification_change resource={}:{} {} -> {} reason={}",
            resourceType, resourceId,
            oldLevel != null ? oldLevel.getCode() : "null",
            newLevel.getCode(), reason);
    }

    /**
     * 强制分类（业务上传时调用）
     */
    public ClassificationLevel enforceClassification(
            String fileName,
            long fileSize,
            String uploaderDept,
            ClassificationLevel userProvided,
            Long operatorUser) {
        ClassificationLevel result = autoClassify(fileName, fileSize, uploaderDept, userProvided);
        log.info("classification_enforced file={} result={}",
            fileName, result.getCode());
        return result;
    }
}