package com.example.edam.security.classification;

import com.example.edam.model.DataClassificationAudit;
import com.example.edam.repository.DataClassificationAuditRepository;
import com.example.edam.util.TimeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
     * 自动分类（<b>纯自动</b>，不看客户端声明）。
     *
     * <p>识别维度：文件名关键词 → 文件大小 → 上传者部门，取最高。
     *
     * <p><b>为什么没有 userProvided 参数</b>：原实现里"用户手动选择的密级优先"
     * 是直接 {@code return userProvided} —— 客户端传什么就是什么，
     * 服务端的识别逻辑一行都跑不到。这条短路正是"密级由客户端决定"的根源，
     * 已删除。现在"客户端声明"与"服务端基线"的合并规则集中在
     * {@link ClassificationEnforcement#resolveUploadLevel}，
     * 规则是<b>只能往严里选</b>。
     *
     * @param fileName     文件名
     * @param fileSize     文件大小（字节）
     * @param uploaderDept 上传者部门名（服务端可信数据）
     * @return 识别出的密级
     */
    public ClassificationLevel autoClassify(
            String fileName,
            long fileSize,
            String uploaderDept
    ) {
        // 1. 文件名关键词识别
        ClassificationLevel nameLevel = classifyByFileName(fileName);
        if (nameLevel.getLevel() >= ClassificationLevel.L3_CONFIDENTIAL.getLevel()) {
            log.info("classification_by_filename level={}", nameLevel.getCode());
            return nameLevel;
        }

        // 2. 文件大小识别
        ClassificationLevel sizeLevel = classifyBySize(fileSize);
        if (sizeLevel.getLevel() >= ClassificationLevel.L3_CONFIDENTIAL.getLevel()) {
            log.info("classification_by_size level={}", sizeLevel.getCode());
            return sizeLevel;
        }

        // 3. 部门识别
        ClassificationLevel deptLevel = classifyByDept(uploaderDept);
        if (deptLevel.getLevel() >= ClassificationLevel.L3_CONFIDENTIAL.getLevel()) {
            log.info("classification_by_dept level={}", deptLevel.getCode());
            return deptLevel;
        }

        // 4. 取最高（文件名 < 大小 < 部门）
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
     * 部门识别。
     *
     * <p><b>用包含匹配而不是全等</b>：{@code sys_dept} 里的部门名带层级后缀
     * （「法务部」「人力资源部」「HR 部」…），全等匹配一个都命中不了 ——
     * 这条规则此前等于从未生效过。
     */
    private ClassificationLevel classifyByDept(String dept) {
        if (dept == null || dept.isBlank()) return ClassificationLevel.L1_PUBLIC;
        String d = dept.toLowerCase();
        if (containsAny(d, "hr", "人力", "人事", "法务", "legal", "审计", "audit")) {
            return ClassificationLevel.L3_CONFIDENTIAL;
        }
        if (containsAny(d, "财务", "finance")) {
            return ClassificationLevel.L2_INTERNAL;
        }
        return ClassificationLevel.L1_PUBLIC;
    }

    private boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) return true;
        }
        return false;
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
        audit.setChangedAt(TimeUtil.nowUtcLocalDateTime());
        auditRepository.insert(audit);

        log.info("classification_change resource={}:{} {} -> {} reason={}",
            resourceType, resourceId,
            oldLevel != null ? oldLevel.getCode() : "null",
            newLevel.getCode(), reason);
    }

    /**
     * 取多个密级中的最高
     */
    public ClassificationLevel max(ClassificationLevel... levels) {
        ClassificationLevel max = ClassificationLevel.L1_PUBLIC;
        for (ClassificationLevel l : levels) {
            if (l.getLevel() > max.getLevel()) max = l;
        }
        return max;
    }
}