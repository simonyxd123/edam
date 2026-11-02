package com.example.edam.service;

import com.example.edam.model.DocPermission;
import com.example.edam.model.DocResource;
import com.example.edam.model.SysUser;
import com.example.edam.repository.DocPermissionRepository;
import com.example.edam.repository.DocResourceRepository;
import com.example.edam.repository.SysUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DocPermissionService} 的单测。
 *
 * <p>与视频侧（{@link VideoPermissionServiceTest}）同构，但**多一条下载规则**：
 * 方案书 5.6.1【例外】"L1 公开资源允许下载；L2-L4 默认禁止" ——
 * 所以下载权**不能**沿用可见性：L2 文档是"能看但不能下"。
 */
class DocPermissionServiceTest {

    private static final Long DOC_ID = 1L;
    private static final Long UPLOADER = 10L;
    private static final Long VIEWER = 20L;
    private static final Long STRANGER = 30L;

    private DocPermissionRepository docPermissionRepository;
    private DocResourceRepository docResourceRepository;
    private SysUserRepository sysUserRepository;
    private DocPermissionService service;

    @BeforeEach
    void setUp() {
        docPermissionRepository = mock(DocPermissionRepository.class);
        docResourceRepository = mock(DocResourceRepository.class);
        sysUserRepository = mock(SysUserRepository.class);
        service = new DocPermissionService(
            docPermissionRepository, docResourceRepository, sysUserRepository);
        when(docPermissionRepository.findByDocAndUser(anyLong(), anyLong()))
            .thenReturn(List.of());
    }

    // ==================================================================
    // 可见性（与视频侧同规则）
    // ==================================================================

    @Test
    @DisplayName("L1/L2：任何登录用户都能看")
    void l1l2VisibleToEveryone() {
        assertTrue(service.canView(doc(1, UPLOADER), STRANGER));
        assertTrue(service.canView(doc(2, UPLOADER), STRANGER));
    }

    @Test
    @DisplayName("L3：非同部门 ⇒ 看不了")
    void l3DeniedForOtherDepartment() {
        when(sysUserRepository.findById(UPLOADER)).thenReturn(user(UPLOADER, 100L));
        when(sysUserRepository.findById(STRANGER)).thenReturn(user(STRANGER, 200L));
        when(sysUserRepository.findListByDeptId(100L)).thenReturn(List.of(user(UPLOADER, 100L)));

        assertFalse(service.canView(doc(3, UPLOADER), STRANGER));
    }

    @Test
    @DisplayName("L3：同部门 ⇒ 能看")
    void l3VisibleToSameDepartment() {
        when(sysUserRepository.findById(STRANGER)).thenReturn(user(STRANGER, 100L));
        when(sysUserRepository.findListByDeptId(100L)).thenReturn(
            List.of(user(UPLOADER, 100L), user(STRANGER, 100L)));

        assertTrue(service.canView(doc(3, UPLOADER), STRANGER));
    }

    @Test
    @DisplayName("L4：没授权 ⇒ 看不了")
    void l4DeniedWithoutGrant() {
        assertFalse(service.canView(doc(4, UPLOADER), STRANGER));
    }

    @Test
    @DisplayName("L4：被显式授权 ⇒ 能看")
    void l4VisibleWithGrant() {
        grantRow(DOC_ID, VIEWER, DocPermissionService.ACTION_VIEW, null);

        assertTrue(service.canView(doc(4, UPLOADER), VIEWER));
    }

    @Test
    @DisplayName("上传者本人：任何密级都能看")
    void uploaderAlwaysSeesOwnDoc() {
        assertTrue(service.canView(doc(4, UPLOADER), UPLOADER));
    }

    // ==================================================================
    // 下载权（与可见性**不同**！）
    // ==================================================================

    @Test
    @DisplayName("L2 文档能看但**不能下载**（方案书 5.6.1 例外条款）")
    void l2VisibleButNotDownloadable() {
        assertTrue(service.canView(doc(2, UPLOADER), STRANGER),
            "L2 是内部级，登录用户都能看");
        assertFalse(service.canDownload(doc(2, UPLOADER), STRANGER),
            "但 L2 默认禁止下载 —— 能看 ≠ 能下");
    }

    @Test
    @DisplayName("L1 公开文档允许下载")
    void l1Downloadable() {
        assertTrue(service.canDownload(doc(1, UPLOADER), STRANGER));
    }

    @Test
    @DisplayName("被授权 download 位 ⇒ 能下载")
    void downloadableWithGrant() {
        grantRow(DOC_ID, VIEWER, DocPermissionService.ACTION_DOWNLOAD, null);

        assertTrue(service.canDownload(doc(4, UPLOADER), VIEWER));
    }

    @Test
    @DisplayName("只有 view 位 ⇒ 能看不能下载")
    void viewOnlyDoesNotGrantDownload() {
        grantRow(DOC_ID, VIEWER, DocPermissionService.ACTION_VIEW, null);

        assertTrue(service.canView(doc(4, UPLOADER), VIEWER));
        assertFalse(service.canDownload(doc(4, UPLOADER), VIEWER));
    }

    @Test
    @DisplayName("过期的 download 授权 ⇒ 不能下载")
    void expiredGrantDoesNotAllowDownload() {
        grantRow(DOC_ID, VIEWER, DocPermissionService.ACTION_DOWNLOAD,
            OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));

        assertFalse(service.canDownload(doc(4, UPLOADER), VIEWER));
    }

    // ==================================================================
    // 动作位
    // ==================================================================

    @Test
    @DisplayName("print 位（32）被识别")
    void printBitIsKnown() {
        grantRow(DOC_ID, VIEWER, DocPermissionService.ACTION_PRINT, null);

        assertEquals(DocPermissionService.ACTION_PRINT,
            service.effectiveActions(DOC_ID, VIEWER));
    }

    @Test
    @DisplayName("grant 拒绝未知的动作位")
    void grantRejectsUnknownBits() {
        when(docResourceRepository.selectById(DOC_ID)).thenReturn(doc(1, UPLOADER));

        try {
            service.grant(DOC_ID, List.of(VIEWER), 64, null, UPLOADER);
            org.junit.jupiter.api.Assertions.fail("应该抛异常");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("未知的动作位"));
        }
    }

    // ==================================================================
    // 落库
    // ==================================================================

    @Test
    @DisplayName("grant 落库：写 actions / granted_by / expire_at")
    void grantPersistsFields() {
        when(docResourceRepository.selectById(DOC_ID)).thenReturn(doc(1, UPLOADER));
        when(sysUserRepository.findById(VIEWER)).thenReturn(user(VIEWER, 100L));
        OffsetDateTime exp = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);

        int written = service.grant(DOC_ID, List.of(VIEWER),
            DocPermissionService.ACTION_VIEW, exp, UPLOADER);

        assertEquals(1, written);
        ArgumentCaptor<DocPermission> captor = ArgumentCaptor.forClass(DocPermission.class);
        verify(docPermissionRepository).insert(captor.<DocPermission>capture());
        DocPermission row = captor.getValue();
        assertEquals(DOC_ID, row.getDocId());
        assertEquals(VIEWER, row.getUserId());
        assertEquals(DocPermissionService.ACTION_VIEW, row.getActions());
        assertEquals(UPLOADER, row.getGrantedBy());
        assertEquals(exp, row.getExpireAt());
    }

    @Test
    @DisplayName("grant 对同一用户是覆盖而不是叠加")
    void grantOverwritesExistingRow() {
        when(docResourceRepository.selectById(DOC_ID)).thenReturn(doc(1, UPLOADER));
        when(sysUserRepository.findById(VIEWER)).thenReturn(user(VIEWER, 100L));
        DocPermission existing = new DocPermission();
        existing.setId(99L);
        existing.setDocId(DOC_ID);
        existing.setUserId(VIEWER);
        existing.setActions(DocPermissionService.ACTION_VIEW);
        when(docPermissionRepository.findByDocAndUser(DOC_ID, VIEWER)).thenReturn(List.of(existing));

        service.grant(DOC_ID, List.of(VIEWER), DocPermissionService.ACTION_DOWNLOAD, null, UPLOADER);

        verify(docPermissionRepository, never()).insert(any(DocPermission.class));
        verify(docPermissionRepository).updateById(existing);
        assertEquals(DocPermissionService.ACTION_DOWNLOAD, existing.getActions());
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private void grantRow(Long docId, Long userId, int actions, OffsetDateTime expireAt) {
        DocPermission row = new DocPermission();
        row.setId(1L);
        row.setDocId(docId);
        row.setUserId(userId);
        row.setActions(actions);
        row.setExpireAt(expireAt);
        row.setGrantedAt(OffsetDateTime.now(ZoneOffset.UTC));
        when(docPermissionRepository.findByDocAndUser(eq(docId), eq(userId)))
            .thenReturn(List.of(row));
    }

    private static DocResource doc(int classificationLv, Long uploaderId) {
        DocResource d = new DocResource();
        d.setId(DOC_ID);
        d.setClassificationLv(classificationLv);
        d.setUploaderId(uploaderId);
        return d;
    }

    private static SysUser user(Long id, Long deptId) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setDeptId(deptId);
        return u;
    }
}
