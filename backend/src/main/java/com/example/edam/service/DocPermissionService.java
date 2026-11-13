package com.example.edam.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.edam.model.DocPermission;
import com.example.edam.model.DocResource;
import com.example.edam.model.SysUser;
import com.example.edam.repository.DocPermissionRepository;
import com.example.edam.repository.DocResourceRepository;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.security.CurrentUser;
import com.example.edam.security.GrantScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档细粒度授权服务（表 {@code doc_permission}）。
 *
 * <p>与 {@link VideoPermissionService} 同构，多一个 {@code print} 位
 * （依据 {@code doc/database_schema.md} §3.2）。
 *
 * <p><b>可见性 = 密级 + 显式授权</b>（方案书 6.3.1），与视频侧一致：
 * <ul>
 *   <li>L1 公开 / L2 内部 ⇒ 任何登录用户可见</li>
 *   <li>L3 机密 ⇒ 上传者、同部门、被显式授权</li>
 *   <li>L4 绝密 ⇒ 上传者、被显式授权</li>
 * </ul>
 *
 * <p><b>动作位</b>：{@code 1=view 2=download 4=edit 8=delete 16=distribute 32=print}。
 * 方案书 5.4 的"禁止复制/下载""禁止打印"落在这几位上。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocPermissionService {

    /** 查看（能预览） */
    public static final int ACTION_VIEW = 1;
    /** 下载原文 */
    public static final int ACTION_DOWNLOAD = 2;
    /** 编辑元数据 */
    public static final int ACTION_EDIT = 4;
    /** 删除 */
    public static final int ACTION_DELETE = 8;
    /** 再分享 */
    public static final int ACTION_DISTRIBUTE = 16;
    /** 打印（方案书 5.4「禁止打印」） */
    public static final int ACTION_PRINT = 32;

    /** 全部动作 */
    public static final int ACTION_ALL =
        ACTION_VIEW | ACTION_DOWNLOAD | ACTION_EDIT | ACTION_DELETE | ACTION_DISTRIBUTE | ACTION_PRINT;

    /** 各位的名字（按下标对应）—— 用于拼出用户看得懂的错误信息 */
    private static final String[] BIT_NAMES = {
        "查看", "下载", "编辑", "删除", "再分享", "打印"
    };

    /** L3：同部门可见 */
    private static final int LEVEL_L3 = 3;

    private final DocPermissionRepository docPermissionRepository;
    private final DocResourceRepository docResourceRepository;
    private final SysUserRepository sysUserRepository;

    // ==================================================================
    // 授权的写入
    // ==================================================================

    /**
     * 批量授权。
     *
     * @param docId      文档
     * @param userIds    被授权人
     * @param actions    动作位掩码
     * @param expireAt   过期时间；null = 永不过期
     * @param grantedBy  授权人
     * @return 实际写入条数
     */
    @Transactional
    public int grant(Long docId, List<Long> userIds, int actions,
                     OffsetDateTime expireAt, Long grantedBy) {
        DocResource doc = docResourceRepository.selectById(docId);
        if (doc == null || doc.getDeletedAt() != null) {
            throw new IllegalArgumentException("文档不存在: " + docId);
        }
        if (actions == 0) {
            throw new IllegalArgumentException("actions 不能为 0（至少要有一个动作位）");
        }
        if ((actions & ~ACTION_ALL) != 0) {
            throw new IllegalArgumentException(
                "未知的动作位: " + actions
                + "（合法位：1=view 2=download 4=edit 8=delete 16=distribute 32=print）");
        }

        assertGrantScope(doc, actions, expireAt, grantedBy);

        int written = 0;
        for (Long userId : userIds) {
            if (userId == null) {
                continue;
            }
            SysUser target = sysUserRepository.findById(userId);
            if (target == null) {
                log.warn("doc_permission_skip_missing_user, doc_id={}, user_id={}", docId, userId);
                continue;
            }
            DocPermission row = findRow(docId, userId);
            if (row == null) {
                row = new DocPermission();
                row.setDocId(docId);
                row.setUserId(userId);
            }
            row.setActions(actions);
            row.setGrantedBy(grantedBy);
            row.setExpireAt(expireAt);
            if (row.getId() == null) {
                docPermissionRepository.insert(row);
            } else {
                docPermissionRepository.updateById(row);
            }
            written++;
        }
        log.info("doc_permission_granted, doc_id={}, granted_by={}, actions={}, expire_at={}, count={}",
            docId, grantedBy, actions, expireAt, written);
        return written;
    }

    /**
     * 转授时的权限上限校验（**防"越权转授"**）。
     *
     * <p><b>这个漏洞是什么</b>（2026-11-11 代码审计第三轮 §1，定级 critical）：
     *
     * <p>{@link #canDistribute} 只检查一个条件：<b>持有 distribute 位</b>。
     * 而 {@code grant()} 对<b>要授出去的位</b>只校验"是不是合法位"，
     * <b>完全不校验授权人自己有没有这些位</b>。
     *
     * <p>于是：上传者 A 给同事 B 授权 {@code view|distribute}（17）——
     * 这是"允许 B 转发"的常规操作。B 拿着这 17 去调
     * {@code POST /documents/batch-permission}，body 写
     * {@code actions=63}（= {@code ACTION_ALL}）。
     * 后端放行（B 有 distribute 位）、写库（63 是合法位）⇒
     * <b>C 拿到了这份 L3 文档的下载、编辑、删除、打印权</b> ——
     * 而 A 从头到尾只给过 B "看 + 转发"。
     *
     * <p><b>B 的权限被放大了 3.7 倍。</b> 这正是方案书 §5.4
     * 「越权分享自动阻断」要防的事：<b>不能拿着别人的授权去扩大授权范围</b>。
     *
     * <p><b>规则</b>：要授出的权限不得超过授权人自己持有的权限
     * （位掩码 + 有效期两个维度，见 {@link GrantScope}）。
     *
     * <p><b>例外</b>：管理员与上传者本人不受限制 ——
     * 上传者对**自己上传的文档**天然全权（见 {@link #canView} 的判定顺序），
     * 管理员则本来就是全权。限制他们反而会让"上传者忘了先给自己授权"
     * 这种死锁无解。
     */
    private void assertGrantScope(DocResource doc, int actions,
                                  OffsetDateTime expireAt, Long grantedBy) {
        if (grantedBy == null) {
            throw new AccessDeniedException("无法识别授权人");
        }
        // 管理员 / 上传者本人 —— 不受限
        if (CurrentUser.isAdmin() || grantedBy.equals(doc.getUploaderId())) {
            return;
        }

        // 纵深防御：授权人自己必须持有 distribute 位。
        //
        // Controller 里已经查过 canDistribute，但**规则要落在写入点**
        // —— {@code grant()} 是权限写入的唯一入口，
        // 将来多一个调用方（或有人注释掉 Controller 的检查）时，这道闸还在。
        if (!canDistribute(doc, grantedBy)) {
            throw new AccessDeniedException("无权分享该文档（需要持有再分享授权）");
        }

        // 授权人自己持有的权限位（显式授权位 ∪ 密级兜底位）
        int ownBits = ownActionBits(doc, grantedBy);
        // 授权人自己那份额权的到期时间（null = 永不过期）
        OffsetDateTime ownExpireAt = ownExpireAt(doc, grantedBy);

        GrantScope.assertCanGrant(ownBits, ownExpireAt, actions, expireAt,
            BIT_NAMES, "文档 " + doc.getId());
    }

    /**
     * 授权人自己在这份文档上**实际持有**的权限位。
     *
     * <p>不只是显式授权位 —— 还要算上密级兜底：
     * <ul>
     *   <li>L1/L2：任何登录用户都能看（{@link #canView}）⇒ 至少有 view 位</li>
     *   <li>L1：公开资源允许下载（方案书 5.6.1【例外】）⇒ 还有 download 位</li>
     * </ul>
     *
     * <p>为什么必须算上：**否则会出现"能转发却不能转授 view"这种自相矛盾** ——
     * 一个持有 distribute 位的 L2 文档被授权人，转手授出去时
     * 连对方"看"都不行（而对方本来就能看，因为 L2 谁都能看）。
     */
    private int ownActionBits(DocResource doc, Long userId) {
        int bits = effectiveActions(doc.getId(), userId);
        if (canView(doc, userId)) {
            bits |= ACTION_VIEW;
        }
        if (canDownload(doc, userId)) {
            bits |= ACTION_DOWNLOAD;
        }
        return bits;
    }

    /**
     * 授权人自己那份额权的到期时间（null = 永不过期 / 没有显式授权）。
     */
    private OffsetDateTime ownExpireAt(DocResource doc, Long userId) {
        DocPermission row = findRow(doc.getId(), userId);
        if (row == null || isExpired(row)) {
            return null;
        }
        return row.getExpireAt();
    }

    /**
     * 撤销授权。
     */
    @Transactional
    public int revoke(Long docId, List<Long> userIds) {
        int removed = 0;
        for (Long userId : userIds) {
            if (userId == null) {
                continue;
            }
            DocPermission row = findRow(docId, userId);
            if (row != null) {
                docPermissionRepository.deleteById(row.getId());
                removed++;
            }
        }
        log.info("doc_permission_revoked, doc_id={}, count={}", docId, removed);
        return removed;
    }

    // ==================================================================
    // 授权的查询与判定
    // ==================================================================

    /**
     * 某用户对某文档的<b>有效</b>动作位掩码（过期的不算）。
     */
    public int effectiveActions(Long docId, Long userId) {
        if (docId == null || userId == null) {
            return 0;
        }
        DocPermission row = findRow(docId, userId);
        if (row == null || isExpired(row)) {
            return 0;
        }
        return row.getActions() == null ? 0 : row.getActions();
    }

    /**
     * 某用户能否<b>查看</b>该文档（= 能预览）。
     *
     * <p>判定顺序与视频侧完全一致：
     * <ol>
     *   <li>管理员 —— 全权</li>
     *   <li>上传者本人 —— 全权</li>
     *   <li>有未过期的 view 授权 —— 可以（显式授权优先于密级）</li>
     *   <li>L1/L2 —— 可以</li>
     *   <li>L3 —— 上传者同部门时可以</li>
     *   <li>否则 —— 不可以</li>
     * </ol>
     */
    public boolean canView(DocResource doc, Long userId) {
        if (doc == null || userId == null) {
            return false;
        }
        if (CurrentUser.isAdmin()) {
            return true;
        }
        if (userId.equals(doc.getUploaderId())) {
            return true;
        }
        if ((effectiveActions(doc.getId(), userId) & ACTION_VIEW) != 0) {
            return true;
        }
        Integer lv = doc.getClassificationLv();
        if (lv == null || lv <= 2) {
            return true;
        }
        if (lv == LEVEL_L3) {
            return colleagueUploaderIds(userId).contains(doc.getUploaderId());
        }
        return false;
    }

    /**
     * 某用户能否<b>下载</b>该文档原文。
     *
     * <p>方案书 5.6.1【例外】"L1 公开资源允许下载；L2-L4 默认禁止"。
     * 所以下载**不能**沿用可见性 —— L2 文档是"能看但不能下"。
     */
    public boolean canDownload(DocResource doc, Long userId) {
        if (doc == null || userId == null) {
            return false;
        }
        if (CurrentUser.isAdmin()) {
            return true;
        }
        if (userId.equals(doc.getUploaderId())) {
            return true;
        }
        Integer lv = doc.getClassificationLv();
        if (lv != null && lv <= 1) {
            return true;                       // L1 公开：允许下载
        }
        return (effectiveActions(doc.getId(), userId) & ACTION_DOWNLOAD) != 0;
    }

    /**
     * 某用户能否把该文档<b>再分享</b>给别人。
     */
    public boolean canDistribute(DocResource doc, Long userId) {
        if (doc == null || userId == null) {
            return false;
        }
        if (CurrentUser.isAdmin()) {
            return true;
        }
        if (userId.equals(doc.getUploaderId())) {
            return true;
        }
        return (effectiveActions(doc.getId(), userId) & ACTION_DISTRIBUTE) != 0;
    }

    /**
     * 列出某文档的全部授权。
     */
    public List<DocPermission> listByDoc(Long docId) {
        return docPermissionRepository.findByDoc(docId);
    }

    /**
     * 给文档列表查询加可见性过滤（与视频侧同源）。
     */
    public void applyVisibility(LambdaQueryWrapper<DocResource> wrapper, Long userId) {
        if (CurrentUser.isAdmin()) {
            return;
        }
        if (userId == null) {
            wrapper.le(DocResource::getClassificationLv, 2);
            return;
        }

        List<Long> grantedIds = new ArrayList<>();
        for (DocPermission p : findAllByUser(userId)) {
            if (isExpired(p)) {
                continue;
            }
            if (((p.getActions() == null ? 0 : p.getActions()) & ACTION_VIEW) == 0) {
                continue;
            }
            if (p.getDocId() != null) {
                grantedIds.add(p.getDocId());
            }
        }

        List<Long> deptUploaderIds = colleagueUploaderIds(userId);

        wrapper.and(w -> {
            w.eq(DocResource::getUploaderId, userId)
             .or().le(DocResource::getClassificationLv, 2);
            if (!grantedIds.isEmpty()) {
                w.or().in(DocResource::getId, grantedIds);
            }
            if (!deptUploaderIds.isEmpty()) {
                w.or().and(x -> x.eq(DocResource::getClassificationLv, LEVEL_L3)
                                  .in(DocResource::getUploaderId, deptUploaderIds));
            }
        });
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private DocPermission findRow(Long docId, Long userId) {
        List<DocPermission> rows = docPermissionRepository.findByDocAndUser(docId, userId);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return rows.stream()
            .max((a, b) -> compareGrantedAt(a, b))
            .orElse(null);
    }

    private static int compareGrantedAt(DocPermission a, DocPermission b) {
        OffsetDateTime ta = a.getGrantedAt();
        OffsetDateTime tb = b.getGrantedAt();
        if (ta == null && tb == null) {
            return 0;
        }
        if (ta == null) {
            return -1;
        }
        if (tb == null) {
            return 1;
        }
        return ta.compareTo(tb);
    }

    /** 授权是否已过期（时间口径统一 UTC，见 MyMetaObjectHandler） */
    private boolean isExpired(DocPermission row) {
        OffsetDateTime expireAt = row.getExpireAt();
        if (expireAt == null) {
            return false;
        }
        return !expireAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC));
    }

    /** 我所在部门里同事们上传的文档的上传者 id */
    private List<Long> colleagueUploaderIds(Long userId) {
        SysUser me = sysUserRepository.findById(userId);
        if (me == null || me.getDeptId() == null) {
            return new ArrayList<>();
        }
        List<SysUser> colleagues = sysUserRepository.findListByDeptId(me.getDeptId());
        List<Long> ids = new ArrayList<>();
        for (SysUser c : colleagues) {
            if (c.getId() != null && !c.getId().equals(userId)) {
                ids.add(c.getId());
            }
        }
        return ids;
    }

    /** 某用户对多个文档的授权（列表过滤用） */
    public List<DocPermission> findAllByUser(Long userId) {
        if (userId == null) {
            return new ArrayList<>();
        }
        return docPermissionRepository.selectList(
            Wrappers.<DocPermission>lambdaQuery().eq(DocPermission::getUserId, userId));
    }
}
