package com.example.edam.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.edam.model.SysUser;
import com.example.edam.model.VideoPermission;
import com.example.edam.model.VideoResource;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.repository.VideoPermissionRepository;
import com.example.edam.repository.VideoResourceRepository;
import com.example.edam.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 视频细粒度授权服务（表 {@code video_permission}）。
 *
 * <p><b>授权模型</b> —— 依据 {@code doc/database_schema.md} §3.1、
 * {@code doc/openapi.yaml} 的 {@code /videos/batch-permission} 契约，
 * 以及方案书 5.4「细粒度权限与外发管控」与 6.3.1「密级定义模型（L1-L4）」：
 *
 * <ul>
 *   <li><b>动作位掩码</b>：{@code 1=view 2=download 4=edit 8=delete 16=distribute}。
 *       同一 (video, user) 唯一 ⇒ 再次授权是<b>覆盖</b>而不是叠加。</li>
 *   <li><b>有效期</b>：{@code expire_at} 为空 = 永不过期；非空则到期即失效。</li>
 *   <li><b>再分享</b>：只有带 {@code distribute} 位的人才能把授权转授给别人
 *       （方案书 5.4「越权分享自动阻断」）。</li>
 * </ul>
 *
 * <p><b>可见性 = 密级 + 显式授权</b>（方案书 6.3.1）：
 * <table border="1">
 *   <caption>各密级的可见范围</caption>
 *   <tr><th>密级</th><th>谁能看</th></tr>
 *   <tr><td>L1 公开</td><td>任何登录用户</td></tr>
 *   <tr><td>L2 内部</td><td>任何登录用户（内部员工）</td></tr>
 *   <tr><td>L3 机密</td><td>上传者本人、同部门、或被显式授权</td></tr>
 *   <tr><td>L4 绝密</td><td>上传者本人、或被显式授权（"少数核心人员"）</td></tr>
 * </table>
 *
 * <p><b>显式授权永远优先</b>：它是"项目组"这个维度的唯一载体
 * （{@code video_permission} 是逐用户的，没有部门字段）。
 *
 * <p><b>为什么上传者天然有全部权限</b>：视频是他上传的，方案书未要求上传者
 * 给自己授权；做成"上传者天然全权"能避免"视频上传了但谁都看不了"的死角。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoPermissionService {

    /** 查看 */
    public static final int ACTION_VIEW = 1;
    /** 下载原片 */
    public static final int ACTION_DOWNLOAD = 2;
    /** 编辑元数据 */
    public static final int ACTION_EDIT = 4;
    /** 删除 */
    public static final int ACTION_DELETE = 8;
    /** 再分享（把授权转授给别人） */
    public static final int ACTION_DISTRIBUTE = 16;

    /** 全部动作 */
    public static final int ACTION_ALL = ACTION_VIEW | ACTION_DOWNLOAD | ACTION_EDIT | ACTION_DELETE | ACTION_DISTRIBUTE;

    /** L3：同部门可见 */
    private static final int LEVEL_L3 = 3;

    private final VideoPermissionRepository videoPermissionRepository;
    private final VideoResourceRepository videoResourceRepository;
    private final SysUserRepository sysUserRepository;

    // ==================================================================
    // 授权的写入
    // ==================================================================

    /**
     * 批量授权（对应 {@code POST /videos/batch-permission}）。
     *
     * @param videoId     视频
     * @param userIds     被授权人
     * @param actions     动作位掩码
     * @param expireAt    过期时间；null = 永不过期
     * @param grantedBy   授权人
     * @return 实际写入条数
     */
    @Transactional
    public int grant(Long videoId, List<Long> userIds, int actions,
                     OffsetDateTime expireAt, Long grantedBy) {
        VideoResource video = videoResourceRepository.selectById(videoId);
        if (video == null || video.getDeletedAt() != null) {
            throw new IllegalArgumentException("视频不存在: " + videoId);
        }
        if (actions == 0) {
            throw new IllegalArgumentException("actions 不能为 0（至少要有一个动作位）");
        }
        if ((actions & ~ACTION_ALL) != 0) {
            throw new IllegalArgumentException(
                "未知的动作位: " + actions + "（合法位：1=view 2=download 4=edit 8=delete 16=distribute）");
        }

        int written = 0;
        for (Long userId : userIds) {
            if (userId == null) {
                continue;
            }
            SysUser target = sysUserRepository.findById(userId);
            if (target == null) {
                log.warn("video_permission_skip_missing_user, video_id={}, user_id={}", videoId, userId);
                continue;
            }
            VideoPermission row = findRow(videoId, userId);
            if (row == null) {
                row = new VideoPermission();
                row.setVideoId(videoId);
                row.setUserId(userId);
            }
            row.setActions(actions);
            row.setGrantedBy(grantedBy);
            row.setExpireAt(expireAt);
            if (row.getId() == null) {
                videoPermissionRepository.insert(row);
            } else {
                videoPermissionRepository.updateById(row);
            }
            written++;
        }
        log.info("video_permission_granted, video_id={}, granted_by={}, actions={}, expire_at={}, count={}",
            videoId, grantedBy, actions, expireAt, written);
        return written;
    }

    /**
     * 撤销授权（把该用户对视频的授权行删掉 ⇒ 对方回到"没有显式授权"）。
     *
     * @return 实际撤销条数
     */
    @Transactional
    public int revoke(Long videoId, List<Long> userIds) {
        int removed = 0;
        for (Long userId : userIds) {
            if (userId == null) {
                continue;
            }
            VideoPermission row = findRow(videoId, userId);
            if (row != null) {
                videoPermissionRepository.deleteById(row.getId());
                removed++;
            }
        }
        log.info("video_permission_revoked, video_id={}, count={}", videoId, removed);
        return removed;
    }

    // ==================================================================
    // 授权的查询与判定
    // ==================================================================

    /**
     * 某用户对某视频的<b>有效</b>动作位掩码（过期的不算）。
     */
    public int effectiveActions(Long videoId, Long userId) {
        if (videoId == null || userId == null) {
            return 0;
        }
        VideoPermission row = findRow(videoId, userId);
        if (row == null || isExpired(row)) {
            return 0;
        }
        return row.getActions() == null ? 0 : row.getActions();
    }

    /**
     * 某用户能否<b>查看</b>该视频。
     *
     * <p>判定顺序（先到先得）：
     * <ol>
     *   <li>管理员 —— 全权</li>
     *   <li>上传者本人 —— 全权</li>
     *   <li>有未过期的 view 授权 —— 可以（显式授权优先于密级）</li>
     *   <li>L1/L2 —— 可以（公开 / 仅限内部员工）</li>
     *   <li>L3 —— 上传者同部门时可以</li>
     *   <li>否则 —— 不可以</li>
     * </ol>
     */
    public boolean canView(VideoResource video, Long userId) {
        if (video == null || userId == null) {
            return false;
        }
        if (CurrentUser.isAdmin()) {
            return true;
        }
        if (userId.equals(video.getUploaderId())) {
            return true;
        }
        if ((effectiveActions(video.getId(), userId) & ACTION_VIEW) != 0) {
            return true;
        }
        Integer lv = video.getClassificationLv();
        if (lv == null || lv <= 2) {
            return true;                       // L1 公开 / L2 内部
        }
        if (lv == LEVEL_L3) {
            return sameDepartment(video.getUploaderId(), userId);
        }
        return false;                          // L4 绝密：只能靠显式授权
    }

    /**
     * 某用户能否把该视频<b>再分享</b>给别人（方案书 5.4「越权分享自动阻断」）。
     */
    public boolean canDistribute(VideoResource video, Long userId) {
        if (video == null || userId == null) {
            return false;
        }
        if (CurrentUser.isAdmin()) {
            return true;
        }
        if (userId.equals(video.getUploaderId())) {
            return true;
        }
        return (effectiveActions(video.getId(), userId) & ACTION_DISTRIBUTE) != 0;
    }

    /**
     * 列出某视频的全部授权（管理端展示"谁被授权了"用）。
     */
    public List<VideoPermission> listByVideo(Long videoId) {
        return videoPermissionRepository.findByVideo(videoId);
    }

    /**
     * 给视频列表查询加可见性过滤。
     *
     * <p>与 {@link #canView} 的判定<b>保持一致</b> —— 列表里看得到、
     * 点进去却 403，是最糟的体验。两处必须同源。
     *
     * <p><b>实现方式</b>：把"显式授权的视频 id"和"同部门同事的视频 id"
     * 各自查成一组 id，再用 {@code OR} 拼进 wrapper。
     *
     * <p><b>为什么不用一段裸 SQL</b>：可见性规则要同时看
     * {@code video_resource} / {@code video_permission} / {@code sys_user} 三张表，
     * 写成子查询虽然短，但这段 SQL 没有单测能覆盖 ——
     * 写错了要等上线才炸。用"先查出 id 集合再拼 OR"的方式，
     * 每一段都能单独测，SQL 也只有 MP 生成的那些简单条件。
     *
     * <p><b>代价</b>：多两次查询。对列表这种场景可以接受
     * （对比原来的"任何人都能看任何视频"，这是净收益）。
     *
     * <p>管理员不加过滤（能看全部）；
     * <b>没有用户上下文时只放行 L1/L2</b>（fail-closed）。
     */
    public void applyVisibility(LambdaQueryWrapper<VideoResource> wrapper, Long userId) {
        if (CurrentUser.isAdmin()) {
            return;
        }
        if (userId == null) {
            wrapper.le(VideoResource::getClassificationLv, 2);
            return;
        }

        // 显式授权（含 view 位且未过期）的视频 id
        List<Long> grantedIds = new ArrayList<>();
        for (VideoPermission p : findAllByUser(userId)) {
            if (isExpired(p)) {
                continue;
            }
            if (((p.getActions() == null ? 0 : p.getActions()) & ACTION_VIEW) == 0) {
                continue;
            }
            if (p.getVideoId() != null) {
                grantedIds.add(p.getVideoId());
            }
        }

        // L3「同部门可见」：我所在部门的同事上传的视频
        List<Long> deptUploaderIds = colleagueUploaderIds(userId);

        wrapper.and(w -> {
            w.eq(VideoResource::getUploaderId, userId)          // 自己上传的
             .or().le(VideoResource::getClassificationLv, 2);      // L1 公开 / L2 内部
            if (!grantedIds.isEmpty()) {
                w.or().in(VideoResource::getId, grantedIds);      // 被显式授权
            }
            if (!deptUploaderIds.isEmpty()) {
                w.or().and(x -> x.eq(VideoResource::getClassificationLv, LEVEL_L3)
                                  .in(VideoResource::getUploaderId, deptUploaderIds));
            }
        });
    }

    /**
     * 我所在部门里，同事们上传的视频的上传者 id。
     *
     * <p>取不到部门（{@code dept_id} 可空）就返回空列表 ——
     * 没有部门就没有"同部门"这回事。
     */
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

    // ==================================================================
    // 内部
    // ==================================================================

    private VideoPermission findRow(Long videoId, Long userId) {
        List<VideoPermission> rows = videoPermissionRepository.findByVideoAndUser(videoId, userId);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        // 唯一键保证只有一行；真有多行（历史脏数据）取最新授权的那行
        return rows.stream()
            .max((a, b) -> compareGrantedAt(a, b))
            .orElse(null);
    }

    private static int compareGrantedAt(VideoPermission a, VideoPermission b) {
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

    /**
     * 授权是否已过期。
     *
     * <p>时间口径统一 UTC：{@code expire_at} 落库时是 UTC 墙钟
     * （见 {@code MyMetaObjectHandler}），这里也用 UTC 比较。
     */
    private boolean isExpired(VideoPermission row) {
        OffsetDateTime expireAt = row.getExpireAt();
        if (expireAt == null) {
            return false;  // 为空 = 永不过期
        }
        return !expireAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 两个用户是否同部门（用于 L3 的密级约束）。
     *
     * <p>任一方没有部门（{@code dept_id} 可空）⇒ 视为不同部门 ——
     * 拿不到部门就当作不满足，宁可严格也不放行。
     */
    private boolean sameDepartment(Long uploaderId, Long userId) {
        SysUser uploader = sysUserRepository.findById(uploaderId);
        SysUser target = sysUserRepository.findById(userId);
        if (uploader == null || target == null) {
            return false;
        }
        if (uploader.getDeptId() == null || target.getDeptId() == null) {
            return false;
        }
        return uploader.getDeptId().equals(target.getDeptId());
    }

    /**
     * 供批量查询用：一次性取回某用户对多个视频的授权。
     */
    public List<VideoPermission> findAllByUser(Long userId) {
        if (userId == null) {
            return new ArrayList<>();
        }
        return videoPermissionRepository.selectList(
            Wrappers.<VideoPermission>lambdaQuery().eq(VideoPermission::getUserId, userId));
    }
}
