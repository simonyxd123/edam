package com.example.edam.service;

import com.example.edam.model.DocPermission;
import com.example.edam.model.DocResource;
import com.example.edam.model.SysUser;
import com.example.edam.model.VideoPermission;
import com.example.edam.model.VideoResource;
import com.example.edam.repository.DocPermissionRepository;
import com.example.edam.repository.DocResourceRepository;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.repository.VideoPermissionRepository;
import com.example.edam.repository.VideoResourceRepository;
import com.example.edam.security.GrantScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * **越权转授**的回归测试（2026-11-11 代码审计第三轮 §1，定级 critical）。
 *
 * <p><b>漏洞是什么</b>：
 * {@code canDistribute()} 只检查"持有 distribute 位"，
 * 而 {@code grant()} 对要授出去的位只校验"是不是合法位"，
 * <b>完全不校验授权人自己有没有这些位</b>。
 *
 * <p><b>攻击路径</b>：
 * <ol>
 *   <li>上传者 A 上传一份 L3 文档，给同事 B 授权
 *       {@code view|distribute}（17）—— "允许 B 转发"的常规操作</li>
 *   <li>B 调 {@code POST /documents/batch-permission}，
 *       body {@code {"doc_id":42,"user_ids":[C],"actions":63}}</li>
 *   <li>后端：{@code canDistribute(B)} → B 有 distribute 位 → <b>放行</b>；
 *       {@code grant(42,[C],63)} → {@code 63 & ~63 == 0} → <b>写库</b></li>
 *   <li>C 现在拿到了这份 L3 文档的<b>下载、编辑、删除、打印</b>权
 *       —— 而 A 从头到尾只给过 B "看 + 转发"</li>
 * </ol>
 *
 * <p><b>B 的权限被放大了 3.7 倍。</b>
 *
 * <p><b>为什么这条在防泄密系统里格外严重</b>：
 * <ul>
 *   <li>download 位 = <b>拿到原文</b>（L3 文档原文出了系统就是一次实打实的泄密）</li>
 *   <li>delete 位 = <b>销毁证据</b>（B 把 A 的文档授权给 C，C 再删掉 ——
 *       审计链上留下的是"C 删的"）</li>
 *   <li>传播链可以拉长：拿到 download 位的人如果同时有 distribute 位
 *       （{@code ACTION_ALL} 里有），就能把 download 继续转授出去</li>
 * </ul>
 *
 * <p>方案书 §5.4「越权分享自动阻断」要防的就是这件事：
 * <b>不能拿着别人的授权去扩大授权范围</b>。
 */
class PrivilegeEscalationTest {

    private static final Long DOC_ID = 42L;
    private static final Long UPLOADER = 10L;   // A：上传者
    private static final Long MIDDLE = 20L;     // B：被授 view|distribute
    private static final Long TARGET = 30L;     // C：被越权授权的目标

    private DocPermissionRepository docPermissionRepository;
    private DocResourceRepository docResourceRepository;
    private SysUserRepository sysUserRepository;
    private DocPermissionService docService;

    private VideoPermissionRepository videoPermissionRepository;
    private VideoResourceRepository videoResourceRepository;
    private VideoPermissionService videoService;

    @BeforeEach
    void setUp() {
        docPermissionRepository = mock(DocPermissionRepository.class);
        docResourceRepository = mock(DocResourceRepository.class);
        sysUserRepository = mock(SysUserRepository.class);
        docService = new DocPermissionService(
            docPermissionRepository, docResourceRepository, sysUserRepository);

        videoPermissionRepository = mock(VideoPermissionRepository.class);
        videoResourceRepository = mock(VideoResourceRepository.class);
        videoService = new VideoPermissionService(
            videoPermissionRepository, videoResourceRepository, sysUserRepository);

        when(docPermissionRepository.findByDocAndUser(anyLong(), anyLong()))
            .thenReturn(List.of());
        when(videoPermissionRepository.findByVideoAndUser(anyLong(), anyLong()))
            .thenReturn(List.of());
        when(sysUserRepository.findById(anyLong())).thenReturn(user(999L));
    }

    // ==================================================================
    // 1. 核心：不能授出自己没有的位
    // ==================================================================

    @Test
    @DisplayName("持 view|distribute 的人授出 ACTION_ALL ⇒ 403（权限被放大 3.7 倍的那条路）")
    void distributeHolderCannotEscalateToAllActions() {
        // L3 文档（同部门才能看）—— 排除"密级兜底让 B 天然有更多位"的干扰
        when(docResourceRepository.selectById(DOC_ID)).thenReturn(doc(3, UPLOADER));
        // B 持有 view|distribute（17）
        docGrantRow(DOC_ID, MIDDLE,
            DocPermissionService.ACTION_VIEW | DocPermissionService.ACTION_DISTRIBUTE, null);

        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
            () -> docService.grant(DOC_ID, List.of(TARGET),
                DocPermissionService.ACTION_ALL, null, MIDDLE));

        assertTrue(ex.getMessage().contains("下载"),
            "错误信息要点名越权的位（下载），实际：" + ex.getMessage());

        // **绝不能**写库
        verify(docPermissionRepository, never()).insert(any(DocPermission.class));
        verify(docPermissionRepository, never()).updateById(any(DocPermission.class));
    }

    @Test
    @DisplayName("持 distribute-only 的人授出 download ⇒ 403")
    void distributeOnlyHolderCannotGrantDownload() {
        when(docResourceRepository.selectById(DOC_ID)).thenReturn(doc(3, UPLOADER));
        docGrantRow(DOC_ID, MIDDLE, DocPermissionService.ACTION_DISTRIBUTE, null);

        assertThrows(AccessDeniedException.class,
            () -> docService.grant(DOC_ID, List.of(TARGET),
                DocPermissionService.ACTION_DOWNLOAD, null, MIDDLE));

        verify(docPermissionRepository, never()).insert(any(DocPermission.class));
    }

    @Test
    @DisplayName("视频侧同构：持 view|distribute 的人授出 ACTION_ALL ⇒ 403")
    void videoDistributeHolderCannotEscalateToAllActions() {
        when(videoResourceRepository.selectById(DOC_ID)).thenReturn(video(3, UPLOADER));
        videoGrantRow(DOC_ID, MIDDLE,
            VideoPermissionService.ACTION_VIEW | VideoPermissionService.ACTION_DISTRIBUTE, null);

        assertThrows(AccessDeniedException.class,
            () -> videoService.grant(DOC_ID, List.of(TARGET),
                VideoPermissionService.ACTION_ALL, null, MIDDLE));

        verify(videoPermissionRepository, never()).insert(any(VideoPermission.class));
    }

    // ==================================================================
    // 2. 正常路径不能被改坏
    // ==================================================================

    @Test
    @DisplayName("上传者授出全部权限 ⇒ 允许（上传者对自己上传的文档天然全权）")
    void uploaderMayGrantEverything() {
        when(docResourceRepository.selectById(DOC_ID)).thenReturn(doc(3, UPLOADER));

        int written = docService.grant(DOC_ID, List.of(TARGET),
            DocPermissionService.ACTION_ALL, null, UPLOADER);

        assertEquals(1, written);
        verify(docPermissionRepository).insert(any(DocPermission.class));
    }

    @Test
    @DisplayName("持 view|distribute|download 的人授出 view|download ⇒ 允许（不放大）")
    void holderMayGrantSubsetOfOwnActions() {
        when(docResourceRepository.selectById(DOC_ID)).thenReturn(doc(3, UPLOADER));
        docGrantRow(DOC_ID, MIDDLE,
            DocPermissionService.ACTION_VIEW | DocPermissionService.ACTION_DISTRIBUTE
                | DocPermissionService.ACTION_DOWNLOAD, null);

        int written = docService.grant(DOC_ID, List.of(TARGET),
            DocPermissionService.ACTION_VIEW | DocPermissionService.ACTION_DOWNLOAD,
            null, MIDDLE);

        assertEquals(1, written);
        verify(docPermissionRepository).insert(any(DocPermission.class));
    }

    @Test
    @DisplayName("持 view|distribute 的人授出 view|distribute ⇒ 允许（转发不放大）")
    void holderMayReShareSameActions() {
        when(docResourceRepository.selectById(DOC_ID)).thenReturn(doc(3, UPLOADER));
        docGrantRow(DOC_ID, MIDDLE,
            DocPermissionService.ACTION_VIEW | DocPermissionService.ACTION_DISTRIBUTE, null);

        int written = docService.grant(DOC_ID, List.of(TARGET),
            DocPermissionService.ACTION_VIEW | DocPermissionService.ACTION_DISTRIBUTE,
            null, MIDDLE);

        assertEquals(1, written);
    }

    @Test
    @DisplayName("完全没有 distribute 位的人调 grant ⇒ 403（纵深防御：规则落在写入点）")
    void holderWithoutDistributeCannotGrantAtAll() {
        when(docResourceRepository.selectById(DOC_ID)).thenReturn(doc(3, UPLOADER));
        // B 只有 view，没有 distribute
        docGrantRow(DOC_ID, MIDDLE, DocPermissionService.ACTION_VIEW, null);

        assertThrows(AccessDeniedException.class,
            () -> docService.grant(DOC_ID, List.of(TARGET),
                DocPermissionService.ACTION_VIEW, null, MIDDLE));

        verify(docPermissionRepository, never()).insert(any(DocPermission.class));
    }

    // ==================================================================
    // 3. 密级兜底位也要算进"自己持有的位"
    // ==================================================================

    @Test
    @DisplayName("L2 文档：密级兜底让 B 天然能看 ⇒ B 可以把 view 授出去")
    void classificationFallbackCountsAsOwnViewBit() {
        // L2：任何登录用户都能看（方案书 6.3.1）
        when(docResourceRepository.selectById(DOC_ID)).thenReturn(doc(2, UPLOADER));
        // B 只有 distribute 位（没有 view）—— 但 L2 谁都能看
        docGrantRow(DOC_ID, MIDDLE, DocPermissionService.ACTION_DISTRIBUTE, null);

        int written = docService.grant(DOC_ID, List.of(TARGET),
            DocPermissionService.ACTION_VIEW, null, MIDDLE);

        assertEquals(1, written,
            "L2 谁都能看 ⇒ B 也有 view 位 ⇒ 授出 view 不算放大");
    }

    @Test
    @DisplayName("L3 文档：没有 view 位的人授出 view ⇒ 403（密级不兜底）")
    void l3WithoutViewBitCannotGrantView() {
        when(docResourceRepository.selectById(DOC_ID)).thenReturn(doc(3, UPLOADER));
        // L3 + 非同部门 + 没有 view 位 ⇒ B 自己也看不到
        docGrantRow(DOC_ID, MIDDLE, DocPermissionService.ACTION_DISTRIBUTE, null);

        assertThrows(AccessDeniedException.class,
            () -> docService.grant(DOC_ID, List.of(TARGET),
                DocPermissionService.ACTION_VIEW, null, MIDDLE));
    }

    // ==================================================================
    // 4. 有效期也是权限的一维
    // ==================================================================

    @Test
    @DisplayName("不能授出比自己那份更长期限的授权")
    void cannotGrantLongerExpiryThanOwn() {
        when(docResourceRepository.selectById(DOC_ID)).thenReturn(doc(3, UPLOADER));
        OffsetDateTime ownExpiry = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);
        docGrantRow(DOC_ID, MIDDLE,
            DocPermissionService.ACTION_VIEW | DocPermissionService.ACTION_DISTRIBUTE, ownExpiry);

        // 授出"永不过期" ⇒ 越界
        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
            () -> docService.grant(DOC_ID, List.of(TARGET),
                DocPermissionService.ACTION_VIEW, null, MIDDLE));
        assertTrue(ex.getMessage().contains("期限"),
            "错误信息要说清是有效期越界，实际：" + ex.getMessage());

        // 授出比自己的还晚 ⇒ 也越界
        assertThrows(AccessDeniedException.class,
            () -> docService.grant(DOC_ID, List.of(TARGET),
                DocPermissionService.ACTION_VIEW,
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(30), MIDDLE));

        // 不晚于自己 ⇒ 允许
        int written = docService.grant(DOC_ID, List.of(TARGET),
            DocPermissionService.ACTION_VIEW,
            OffsetDateTime.now(ZoneOffset.UTC).plusDays(3), MIDDLE);
        assertEquals(1, written);
    }

    // ==================================================================
    // 5. GrantScope 本身（纯函数）
    // ==================================================================

    @Test
    @DisplayName("GrantScope.excessBits：算出越界的位")
    void excessBitsIsCorrect() {
        String[] names = {"查看", "下载", "编辑", "删除", "再分享", "打印"};

        assertEquals(0, GrantScope.excessBits(0b010001, 0b010001),
            "完全相同的位 ⇒ 没有越界");
        assertEquals(0b100010, GrantScope.excessBits(0b010001, 0b110011),
            "view|distribute 持有，授出 view|download|distribute|print ⇒ 越界的是 download+print 位");
        assertEquals(0b101000, GrantScope.excessBits(0b010001, 0b111001),
            "delete + print 位越界");

        // 错误信息要能点名越界的位（0b101000 = delete(8) + print(32)）
        String msg = GrantScope.describeBits(0b101000, names);
        assertTrue(msg.contains("删除") && msg.contains("打印"), "实际：" + msg);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private void docGrantRow(Long docId, Long userId, int actions, OffsetDateTime expireAt) {
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

    private void videoGrantRow(Long videoId, Long userId, int actions, OffsetDateTime expireAt) {
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

    private static DocResource doc(int classificationLv, Long uploaderId) {
        DocResource d = new DocResource();
        d.setId(DOC_ID);
        d.setClassificationLv(classificationLv);
        d.setUploaderId(uploaderId);
        return d;
    }

    private static VideoResource video(int classificationLv, Long uploaderId) {
        VideoResource v = new VideoResource();
        v.setId(DOC_ID);
        v.setClassificationLv(classificationLv);
        v.setUploaderId(uploaderId);
        return v;
    }

    private static SysUser user(Long id) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setDeptId(100L);
        return u;
    }
}
