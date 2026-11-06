package com.example.edam.security.classification;

import com.example.edam.model.SysDept;
import com.example.edam.model.SysUser;
import com.example.edam.repository.DataClassificationAuditRepository;
import com.example.edam.repository.DocResourceRepository;
import com.example.edam.repository.SysDeptRepository;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.repository.VideoResourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 密级裁决的单测。
 *
 * <p>盯的是这条规则：**最终密级 = max(服务端基线, 客户端声明)** ——
 * 客户端可以往严里选，不能往松里选。
 *
 * <p>在此之前密级完全由客户端上传时传的 {@code classification_lv} 决定，
 * 且非法值还会被静默降级成 L1（公开）—— 等于任何人都能把资产声明成公开。
 */
class ClassificationEnforcementTest {

    private static final long ONE_MB = 1024L * 1024L;

    private DataClassificationService classificationService;
    private SysUserRepository userRepository;
    private SysDeptRepository deptRepository;
    private ClassificationEnforcement enforcement;

    @BeforeEach
    void setUp() {
        classificationService = new DataClassificationService(
            mock(DataClassificationAuditRepository.class));
        userRepository = mock(SysUserRepository.class);
        deptRepository = mock(SysDeptRepository.class);
        enforcement = new ClassificationEnforcement(
            classificationService, userRepository, deptRepository,
            mock(VideoResourceRepository.class), mock(DocResourceRepository.class));

        // 默认：上传者属于「技术部」（不敏感），无部门时按无部门处理
        SysUser user = new SysUser();
        user.setId(10L);
        user.setDeptId(2L);
        when(userRepository.selectById(anyLong())).thenReturn(user);
        when(deptRepository.findById(2L)).thenReturn(dept("技术部", 2L));
    }

    // ==================================================================
    // 非法取值
    // ==================================================================

    @Test
    @DisplayName("非法密级直接拒绝，不再静默降级成 L1")
    void illegalValueRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> enforcement.resolveUploadLevel("a.mp4", ONE_MB, 10L, "L9"));
        assertThrows(IllegalArgumentException.class,
            () -> enforcement.resolveUploadLevel("a.mp4", ONE_MB, 10L, "公开"));
        assertThrows(IllegalArgumentException.class,
            () -> enforcement.resolveUploadLevel("a.mp4", ONE_MB, 10L, "l5"));
    }

    // ==================================================================
    // 客户端不能往松里选
    // ==================================================================

    @Test
    @DisplayName("文件名含机密关键词时，声明 L1 会被抬到 L3")
    void sensitiveFileNameRaisesFloor() {
        ClassificationEnforcement.ClassificationDecision d =
            enforcement.resolveUploadLevel("年度薪酬方案.pdf", ONE_MB, 10L, "L1");

        assertEquals(ClassificationLevel.L3_CONFIDENTIAL, d.getFinalLevel());
        assertTrue(d.isRaisedByServer());
    }

    @Test
    @DisplayName("上传者在敏感部门时，声明 L1 会被抬到 L3")
    void sensitiveDepartmentRaisesFloor() {
        SysUser user = new SysUser();
        user.setId(11L);
        user.setDeptId(9L);
        when(userRepository.selectById(11L)).thenReturn(user);
        when(deptRepository.findById(9L)).thenReturn(dept("法务部", 9L));

        ClassificationEnforcement.ClassificationDecision d =
            enforcement.resolveUploadLevel("普通文档.pdf", ONE_MB, 11L, "L1");

        assertEquals(ClassificationLevel.L3_CONFIDENTIAL, d.getFinalLevel());
        assertTrue(d.isRaisedByServer());
    }

    @Test
    @DisplayName("大文件也会抬高基线（但自动识别封顶 L3）")
    void largeFileRaisesFloorButCappedAtL3() {
        // > 5GB 按规则会判成 L4，但自动识别封顶 L3 —— L4 只能人工判定
        ClassificationEnforcement.ClassificationDecision d =
            enforcement.resolveUploadLevel("培训录像.mp4", 6L * 1024 * 1024 * 1024, 10L, "L1");

        assertEquals(ClassificationLevel.L3_CONFIDENTIAL, d.getFinalLevel());
    }

    // ==================================================================
    // 客户端可以往严里选
    // ==================================================================

    @Test
    @DisplayName("声明比基线更严时按声明走")
    void higherDeclarationWins() {
        ClassificationEnforcement.ClassificationDecision d =
            enforcement.resolveUploadLevel("普通文档.pdf", ONE_MB, 10L, "L4");

        assertEquals(ClassificationLevel.L4_TOP_SECRET, d.getFinalLevel());
        assertTrue(!d.isRaisedByServer());
    }

    @Test
    @DisplayName("完全不声明时由服务端定")
    void noDeclarationUsesServerFloor() {
        ClassificationEnforcement.ClassificationDecision d =
            enforcement.resolveUploadLevel("普通文档.pdf", ONE_MB, 10L, null);

        assertEquals(ClassificationLevel.L1_PUBLIC, d.getFinalLevel());
    }

    @Test
    @DisplayName("普通文件声明 L1 就按 L1 落库")
    void ordinaryFileKeepsL1() {
        ClassificationEnforcement.ClassificationDecision d =
            enforcement.resolveUploadLevel("季度汇报.mp4", ONE_MB, 10L, "L1");

        assertEquals(ClassificationLevel.L1_PUBLIC, d.getFinalLevel());
        assertEquals(ClassificationLevel.L1_PUBLIC, d.getRequestedLevel());
    }

    // ==================================================================
    // 自动识别封顶
    // ==================================================================

    @Test
    @DisplayName("文件名含绝密关键词也不自动给 L4 —— L4 只能人工判定")
    void autoDetectionNeverYieldsL4() {
        ClassificationEnforcement.ClassificationDecision d =
            enforcement.resolveUploadLevel("绝密-项目资料.zip", ONE_MB, 10L, null);

        assertEquals(ClassificationLevel.L3_CONFIDENTIAL, d.getFinalLevel());
    }

    // ==================================================================
    // 部门规则
    // ==================================================================

    @Test
    @DisplayName("部门规则用包含匹配，「法务部」能命中（原实现全等匹配，规则等于从未生效）")
    void departmentRuleMatchesSubstring() {
        SysUser user = new SysUser();
        user.setId(12L);
        user.setDeptId(10L);
        when(userRepository.selectById(12L)).thenReturn(user);
        when(deptRepository.findById(10L)).thenReturn(dept("法务部", 10L));

        ClassificationEnforcement.ClassificationDecision d =
            enforcement.resolveUploadLevel("x.pdf", ONE_MB, 12L, "L1");

        assertEquals(ClassificationLevel.L3_CONFIDENTIAL, d.getFinalLevel());
    }

    @Test
    @DisplayName("部门查不到时基线退化为文件名 / 大小，不阻断上传")
    void deptLookupFailureDoesNotBlockUpload() {
        SysUser user = new SysUser();
        user.setId(13L);
        user.setDeptId(99L);
        when(userRepository.selectById(13L)).thenReturn(user);
        when(deptRepository.findById(99L)).thenReturn(null);

        ClassificationEnforcement.ClassificationDecision d =
            enforcement.resolveUploadLevel("季度汇报.mp4", ONE_MB, 13L, "L1");

        assertEquals(ClassificationLevel.L1_PUBLIC, d.getFinalLevel());
    }

    private SysDept dept(String name, Long id) {
        SysDept dept = new SysDept();
        dept.setId(id);
        dept.setName(name);
        return dept;
    }
}
