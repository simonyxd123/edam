package com.example.edam.security.classification;

import com.example.edam.model.DocResource;
import com.example.edam.model.SysDept;
import com.example.edam.model.SysUser;
import com.example.edam.model.VideoResource;
import com.example.edam.repository.DocResourceRepository;
import com.example.edam.repository.SysDeptRepository;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.repository.VideoResourceRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 密级裁决器（v3.3 W-7.3，v3.4.1 接线）
 *
 * <p><b>要解决的问题</b>：v3.4.1 之前，密级完全由客户端上传时传的
 * {@code classification_lv} 决定，服务端不校验也不裁决 ——
 * 任何登录用户都能把自己的资产声明成 {@code L1 公开}，
 * 于是 {@code VideoPermissionService.canView} 里的
 * {@code lv <= 2 → 任何登录用户可见} 就成了公开广播。
 *
 * <p><b>裁决规则</b>（最终密级 = max(服务端基线, 客户端声明)）：
 * <ol>
 *   <li>客户端声明的密级<b>必须合法</b> —— 非法值直接 400，
 *       绝不像从前那样静默降级成 L1</li>
 *   <li>服务端算出一条<b>基线</b>（auto floor）</li>
 *   <li>最终密级取两者中<b>更高</b>的那个 ——
 *       客户端可以往严里选，但不能往松里选</li>
 * </ol>
 *
 * <p><b>基线从哪来</b>：文件名关键词、文件大小、上传者部门。
 * 这里有个必须讲清楚的前提 ——
 * <b>文件名和文件大小都是客户端可控的</b>，把它们当基线只是"便宜的启发式"：
 * 恶意用户把敏感文件改名成「季度汇报」就能绕开文件名关键词。
 * 真正可信的只有<b>上传者部门</b>（服务端 {@code sys_dept} 里的数据）。
 * 内容级的密级识别需要真正的文档分析引擎 —— 那部分在本仓库**尚未实现**，
 * 已记入 README 的「已知限制」。
 *
 * <p><b>为什么自动识别最高只到 L3（机密）</b>：L4「绝密」的语义是
 * "只有上传者本人和被显式授权的人能看"。若允许启发式规则直接产出 L4，
 * 一个 6GB 的培训视频就会变成谁也看不见的资产 —— 自动规则不该有这么大杀伤力。
 * <b>L4 只能由人在上传时显式声明，或事后走
 * {@link #changeClassification} 人工调整</b>（全程留痕）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClassificationEnforcement {

    /** 自动识别能给出的最高密级 —— L4 必须人工判定 */
    public static final ClassificationLevel AUTO_MAX_LEVEL = ClassificationLevel.L3_CONFIDENTIAL;

    private final DataClassificationService classificationService;
    private final SysUserRepository userRepository;
    private final SysDeptRepository deptRepository;
    private final VideoResourceRepository videoRepository;
    private final DocResourceRepository docRepository;

    // ========================================================================
    // 上传时的密级裁决
    // ========================================================================

    /**
     * 裁决上传资源的最终密级。
     *
     * @param fileName       原始文件名
     * @param fileSize       文件大小（字节）
     * @param uploaderUserId 上传者 ID
     * @param requestedLv    客户端声明的密级（L1-L4）；可空（表示不声明）
     * @return 裁决结果
     * @throws IllegalArgumentException 客户端声明的密级非法（→ 400）
     */
    public ClassificationDecision resolveUploadLevel(
            String fileName,
            long fileSize,
            Long uploaderUserId,
            String requestedLv) {

        // 1. 客户端声明的密级：非法直接拒，不降级
        ClassificationLevel requested = null;
        if (requestedLv != null && !requestedLv.isBlank()) {
            requested = ClassificationLevel.fromCodeOrNull(requestedLv);
            if (requested == null) {
                log.warn("classification_invalid_value requested={} uploader_id={}",
                    requestedLv, uploaderUserId);
                throw new IllegalArgumentException(
                    "密级取值非法: '" + requestedLv + "'，合法取值为 L1 / L2 / L3 / L4");
            }
        }

        // 2. 服务端基线
        String deptName = lookupDeptName(uploaderUserId);
        ClassificationLevel autoFloor = autoFloor(fileName, fileSize, deptName);

        // 3. 最终密级 = max(基线, 声明)
        ClassificationLevel finalLevel = autoFloor;
        if (requested != null && requested.getLevel() > finalLevel.getLevel()) {
            finalLevel = requested;
        }

        boolean raised = requested == null
            ? autoFloor.getLevel() > ClassificationLevel.L1_PUBLIC.getLevel()
            : finalLevel.getLevel() > requested.getLevel();

        log.info("classification_resolved file={} requested={} floor={} final={} dept={}",
            fileName,
            requested != null ? requested.getCode() : "null",
            autoFloor.getCode(), finalLevel.getCode(), deptName);

        return new ClassificationDecision(finalLevel, requested, autoFloor, raised, deptName);
    }

    /**
     * 算服务端基线：文件名关键词 / 文件大小 / 上传者部门，取最高，且封顶 L3。
     */
    private ClassificationLevel autoFloor(String fileName, long fileSize, String deptName) {
        // userProvided 传 null —— 走纯自动识别，不让客户端值短路
        ClassificationLevel detected = classificationService.autoClassify(
            fileName, fileSize, deptName);

        if (detected.getLevel() > AUTO_MAX_LEVEL.getLevel()) {
            log.info("classification_auto_capped detected={} capped={}",
                detected.getCode(), AUTO_MAX_LEVEL.getCode());
            return AUTO_MAX_LEVEL;
        }
        return detected;
    }

    /**
     * 查上传者部门名（服务端可信数据）。
     *
     * <p>部门是密级基线里唯一不可由客户端伪造的信号，所以这里取的是
     * {@code sys_user.dept_id} → {@code sys_dept.name}，
     * <b>不是</b>请求参数里的任何东西。
     */
    private String lookupDeptName(Long uploaderUserId) {
        if (uploaderUserId == null) return null;
        try {
            SysUser user = userRepository.selectById(uploaderUserId);
            if (user == null || user.getDeptId() == null) return null;
            SysDept dept = deptRepository.findById(user.getDeptId());
            return dept == null ? null : dept.getName();
        } catch (Exception e) {
            // 查部门失败不该阻断上传 —— 基线退化为只用文件名 / 大小
            log.warn("classification_dept_lookup_failed uploader_id={} error={}",
                uploaderUserId, e.getMessage());
            return null;
        }
    }

    /**
     * 上传后写审计（首次入库，oldLevel 为 null）。
     */
    @Transactional
    public void recordUploadDecision(
            String resourceType,
            Long resourceId,
            ClassificationDecision decision,
            Long operatorId) {
        classificationService.recordChange(
            resourceType, resourceId,
            null,
            decision.getFinalLevel(),
            decision.getReason(),
            operatorId,
            decision.isRaisedByServer() ? "auto" : "user",
            null);
    }

    // ========================================================================
    // 人工变更密级
    // ========================================================================

    /**
     * 人工变更密级。
     *
     * <p>这是<b>唯一</b>能把密级往低调的路径 —— 上传时的裁决只允许往严调。
     * 调用方必须自行校验 {@code classification:manage} 权限。
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
                ? ClassificationLevel.fromCode("L" + video.getClassificationLv())
                : ClassificationLevel.L1_PUBLIC;
            video.setClassificationLv(newLevel.getLevel());
            video.setUpdatedAt(OffsetDateTime.now());
            videoRepository.updateById(video);
        } else {
            DocResource doc = docRepository.selectById(resourceId);
            if (doc == null) return;
            oldLevel = doc.getClassificationLv() != null
                ? ClassificationLevel.fromCode("L" + doc.getClassificationLv())
                : ClassificationLevel.L1_PUBLIC;
            doc.setClassificationLv(newLevel.getLevel());
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

    // ========================================================================
    // DTO
    // ========================================================================

    /**
     * 一次密级裁决的结果。
     */
    @Data
    public static class ClassificationDecision {

        /** 最终落库的密级 */
        private final ClassificationLevel finalLevel;

        /** 客户端声明的密级（没声明则为 null） */
        private final ClassificationLevel requestedLevel;

        /** 服务端算出的基线 */
        private final ClassificationLevel autoFloor;

        /** 最终密级是否高于客户端声明（= 被服务端抬高） */
        private final boolean raisedByServer;

        /** 上传者部门名 */
        private final String uploaderDept;

        public ClassificationDecision(
                ClassificationLevel finalLevel,
                ClassificationLevel requestedLevel,
                ClassificationLevel autoFloor,
                boolean raisedByServer,
                String uploaderDept) {
            this.finalLevel = finalLevel;
            this.requestedLevel = requestedLevel;
            this.autoFloor = autoFloor;
            this.raisedByServer = raisedByServer;
            this.uploaderDept = uploaderDept;
        }

        /**
         * 审计用的变更原因。
         */
        public String getReason() {
            if (raisedByServer) {
                return "上传时服务端裁决抬高（基线 " + autoFloor.getCode()
                    + "，声明 "
                    + (requestedLevel != null ? requestedLevel.getCode() : "未声明") + "）";
            }
            return "上传时按声明密级 " + finalLevel.getCode();
        }
    }
}
