package com.example.edam.service;

import com.example.edam.model.SysUser;
import com.example.edam.model.VideoPermission;
import com.example.edam.model.VideoResource;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.repository.VideoPermissionRepository;
import com.example.edam.repository.VideoResourceRepository;
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
 * {@link VideoPermissionService} 的单测。
 *
 * <p>锁住三件最容易出错的事：
 * <ol>
 *   <li><b>密级兜底</b>：L1/L2 谁都能看，L3 要同部门，L4 只能靠显式授权</li>
 *   <li><b>有效期</b>：过期的授权一律当没授权</li>
 *   <li><b>再分享</b>：没有 {@code distribute} 位的人不能把授权转授出去</li>
 * </ol>
 */
class VideoPermissionServiceTest {

    private static final Long VIDEO_ID = 1L;
    private static final Long UPLOADER = 10L;
    private static final Long VIEWER = 20L;
    private static final Long STRANGER = 30L;

    private VideoPermissionRepository videoPermissionRepository;
    private VideoResourceRepository videoResourceRepository;
    private SysUserRepository sysUserRepository;
    private VideoPermissionService service;

    @BeforeEach
    void setUp() {
        videoPermissionRepository = mock(VideoPermissionRepository.class);
        videoResourceRepository = mock(VideoResourceRepository.class);
        sysUserRepository = mock(SysUserRepository.class);
        service = new VideoPermissionService(
            videoPermissionRepository, videoResourceRepository, sysUserRepository);
        when(videoPermissionRepository.findByVideoAndUser(anyLong(), anyLong()))
            .thenReturn(List.of());
    }

    // ==================================================================
    // 密级兜底
    // ==================================================================

    @Test
    @DisplayName("L1/L2：任何登录用户都能看（不需要显式授权）")
    void l1l2VisibleToEveryone() {
        assertTrue(service.canView(video(1, UPLOADER), STRANGER));
        assertTrue(service.canView(video(2, UPLOADER), STRANGER));
    }

    @Test
    @DisplayName("L3：非同部门、没授权 ⇒ 看不了")
    void l3DeniedForOtherDepartment() {
        // 同部门判定需要两个用户的 dept_id
        when(sysUserRepository.findById(UPLOADER)).thenReturn(user(UPLOADER, 100L));
        when(sysUserRepository.findById(STRANGER)).thenReturn(user(STRANGER, 200L));

        assertFalse(service.canView(video(3, UPLOADER), STRANGER));
    }

    @Test
    @DisplayName("L3：同部门 ⇒ 能看")
    void l3VisibleToSameDepartment() {
        when(sysUserRepository.findById(UPLOADER)).thenReturn(user(UPLOADER, 100L));
        when(sysUserRepository.findById(STRANGER)).thenReturn(user(STRANGER, 100L));

        assertTrue(service.canView(video(3, UPLOADER), STRANGER));
    }

    @Test
    @DisplayName("L4：非同部门 ⇒ 看不了（授权也不能突破密级）")
    void l4DeniedWithoutGrant() {
        when(sysUserRepository.findById(UPLOADER)).thenReturn(user(UPLOADER, 100L));
        when(sysUserRepository.findById(STRANGER)).thenReturn(user(STRANGER, 200L));

        assertFalse(service.canView(video(4, UPLOADER), STRANGER));
    }

    @Test
    @DisplayName("L4：被显式授权 ⇒ 能看（授权是\"少数核心人员\"的载体）")
    void l4VisibleWithGrant() {
        grantRow(VIDEO_ID, VIEWER, VideoPermissionService.ACTION_VIEW, null);

        assertTrue(service.canView(video(4, UPLOADER), VIEWER));
    }

    @Test
    @DisplayName("上传者本人：任何密级都能看自己的视频")
    void uploaderAlwaysSeesOwnVideo() {
        assertTrue(service.canView(video(4, UPLOADER), UPLOADER));
    }

    // ==================================================================
    // 有效期
    // ==================================================================

    @Test
    @DisplayName("过期的授权 ⇒ 当没授权（L4 看不了）")
    void expiredGrantDoesNotCount() {
        grantRow(VIDEO_ID, VIEWER, VideoPermissionService.ACTION_VIEW,
            OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));

        when(sysUserRepository.findById(UPLOADER)).thenReturn(user(UPLOADER, 100L));
        when(sysUserRepository.findById(VIEWER)).thenReturn(user(VIEWER, 200L));
        assertFalse(service.canView(video(4, UPLOADER), VIEWER));
    }

    @Test
    @DisplayName("未过期的授权 ⇒ 生效")
    void unexpiredGrantCounts() {
        grantRow(VIDEO_ID, VIEWER, VideoPermissionService.ACTION_VIEW,
            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10));

        assertTrue(service.canView(video(4, UPLOADER), VIEWER));
    }

    @Test
    @DisplayName("expire_at 为空 = 永不过期")
    void nullExpireNeverExpires() {
        grantRow(VIDEO_ID, VIEWER, VideoPermissionService.ACTION_VIEW, null);

        assertTrue(service.canView(video(4, UPLOADER), VIEWER));
    }

    // ==================================================================
    // 动作位
    // ==================================================================

    @Test
    @DisplayName("只有 download 位（没有 view）⇒ 不能看")
    void downloadOnlyCannotView() {
        grantRow(VIDEO_ID, VIEWER, VideoPermissionService.ACTION_DOWNLOAD, null);

        assertFalse(service.canView(video(4, UPLOADER), VIEWER));
        assertEquals(VideoPermissionService.ACTION_DOWNLOAD,
            service.effectiveActions(VIDEO_ID, VIEWER));
    }

    // ==================================================================
    // 再分享
    // ==================================================================

    @Test
    @DisplayName("没有 distribute 位 ⇒ 不能再分享")
    void cannotDistributeWithoutBit() {
        grantRow(VIDEO_ID, VIEWER, VideoPermissionService.ACTION_VIEW, null);

        assertFalse(service.canDistribute(video(4, UPLOADER), VIEWER));
    }

    @Test
    @DisplayName("有 distribute 位 ⇒ 能再分享")
    void canDistributeWithBit() {
        grantRow(VIDEO_ID, VIEWER,
            VideoPermissionService.ACTION_VIEW | VideoPermissionService.ACTION_DISTRIBUTE, null);

        assertTrue(service.canDistribute(video(4, UPLOADER), VIEWER));
    }

    @Test
    @DisplayName("上传者天然能再分享自己的视频")
    void uploaderCanDistribute() {
        assertTrue(service.canDistribute(video(4, UPLOADER), UPLOADER));
    }

    // ==================================================================
    // 授权的写入
    // ==================================================================

    @Test
    @DisplayName("grant 落库：写 actions / granted_by / expire_at")
    void grantPersistsFields() {
        when(videoResourceRepository.selectById(1L)).thenReturn(video(1, UPLOADER));
        when(sysUserRepository.findById(VIEWER)).thenReturn(user(VIEWER, 100L));
        OffsetDateTime exp = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);

        int written = service.grant(1L, List.of(VIEWER),
            VideoPermissionService.ACTION_VIEW, exp, UPLOADER);

        assertEquals(1, written);
        ArgumentCaptor<VideoPermission> captor = ArgumentCaptor.forClass(VideoPermission.class);
        verify(videoPermissionRepository).insert(captor.<VideoPermission>capture());
        VideoPermission row = captor.getValue();
        assertEquals(1L, row.getVideoId());
        assertEquals(VIEWER, row.getUserId());
        assertEquals(VideoPermissionService.ACTION_VIEW, row.getActions());
        assertEquals(UPLOADER, row.getGrantedBy());
        assertEquals(exp, row.getExpireAt());
    }

    @Test
    @DisplayName("grant 对同一用户是覆盖而不是叠加（唯一键 uk_video_user）")
    void grantOverwritesExistingRow() {
        when(videoResourceRepository.selectById(1L)).thenReturn(video(1, UPLOADER));
        when(sysUserRepository.findById(VIEWER)).thenReturn(user(VIEWER, 100L));
        VideoPermission existing = new VideoPermission();
        existing.setId(99L);
        existing.setVideoId(1L);
        existing.setUserId(VIEWER);
        existing.setActions(VideoPermissionService.ACTION_VIEW);
        when(videoPermissionRepository.findByVideoAndUser(1L, VIEWER)).thenReturn(List.of(existing));

        service.grant(1L, List.of(VIEWER), VideoPermissionService.ACTION_DOWNLOAD, null, UPLOADER);

        verify(videoPermissionRepository, never()).insert(any(VideoPermission.class));
        verify(videoPermissionRepository).updateById(existing);
        assertEquals(VideoPermissionService.ACTION_DOWNLOAD, existing.getActions());
    }

    @Test
    @DisplayName("grant 拒绝未知的动作位")
    void grantRejectsUnknownBits() {
        when(videoResourceRepository.selectById(1L)).thenReturn(video(1, UPLOADER));

        try {
            service.grant(1L, List.of(VIEWER), 999, null, UPLOADER);
            org.junit.jupiter.api.Assertions.fail("应该抛异常");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("未知的动作位"));
        }
    }

    @Test
    @DisplayName("revoke 删掉授权行")
    void revokeDeletesRow() {
        VideoPermission existing = new VideoPermission();
        existing.setId(99L);
        when(videoPermissionRepository.findByVideoAndUser(1L, VIEWER)).thenReturn(List.of(existing));

        assertEquals(1, service.revoke(1L, List.of(VIEWER)));
        verify(videoPermissionRepository).deleteById(99L);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private void grantRow(Long videoId, Long userId, int actions, OffsetDateTime expireAt) {
        VideoPermission row = new VideoPermission();
        row.setId(1L);
        row.setVideoId(videoId);
        row.setUserId(userId);
        row.setActions(actions);
        row.setExpireAt(expireAt);
        row.setGrantedAt(OffsetDateTime.now(ZoneOffset.UTC));
        when(videoPermissionRepository.findByVideoAndUser(eq(videoId), eq(userId)))
            .thenReturn(List.of(row));
    }

    private static VideoResource video(int classificationLv, Long uploaderId) {
        VideoResource v = new VideoResource();
        v.setId(1L);
        v.setClassificationLv(classificationLv);
        v.setUploaderId(uploaderId);
        return v;
    }

    private static SysUser user(Long id, Long deptId) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setDeptId(deptId);
        return u;
    }
}
