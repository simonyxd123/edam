package com.example.edam.security;

import java.util.Set;

/**
 * 普通员工（{@code employee} 角色）的权限基线。
 *
 * <p><b>这条基线是什么</b>：普通员工**只读** —— 只能看列表、详情、播放、预览，
 * 任何写操作（上传 / 编辑 / 删除 / 分享 / 下载原文 / 外发审批 / 管理类操作）
 * 一律没有授权码，{@code @PreAuthorize} 直接 403。
 *
 * <p><b>为什么要显式写死成白名单</b>（而不是"把 upload 那几个删掉"）：
 * 权限目录（{@code sys_permission}）会随功能增长而变多。如果只做减法，
 * 以后有人新增了一个 {@code xxx:export} 权限、又在管理界面上把它勾给了
 * employee，普通员工就会**默默地**多出一项写权限 —— 这正是防泄密系统
 * 最不能接受的漂移。白名单的语义是"**没显式列出来的，一律没有**"：
 * 新权限默认不给 employee，要给必须在代码和迁移里各写一遍。
 *
 * <p><b>白名单里的每一项都必须是 {@code action = 'read'} 的权限</b>。
 * 这一点由 {@code EmployeePermissionsTest} 守着 —— 它拿权限目录
 * （{@code V20260830_1000__rbac_seed.sql}）反查每一项的 action，
 * 只要白名单里混进一个非 read 的权限码，测试就红。
 *
 * <p><b>权威来源</b>：真正生效的是数据库里的 {@code sys_role_permission}。
 * 本类是"我们希望 employee 长什么样"的声明，
 * 由迁移 {@code V20261105_1000__employee_readonly_role.sql} 落到数据库，
 * 两者由同一个测试保证不漂移。
 *
 * <p><b>角色分层</b>（配合 {@code dept_manager} / {@code auditor}）：
 * <ul>
 *   <li>{@code admin} —— 全部权限（{@code PermissionService} 短路返回权限目录全集）</li>
 *   <li>{@code dept_manager} —— 本部门资源的上传 / 编辑 / 分享 / 审批 / 删除</li>
 *   <li>{@code employee} —— <b>只读</b>（本类定义的白名单）</li>
 *   <li>{@code auditor} —— 只读 + 审计导出</li>
 * </ul>
 */
public final class EmployeePermissions {

    /** 普通员工的角色码。 */
    public static final String ROLE_CODE = "employee";

    /**
     * 普通员工的只读白名单（权限码全集）。
     *
     * <p>每一项都必须是权限目录里 {@code action = 'read'} 的条目。
     * 改动这里必须同步改迁移 {@code V20261105_1000__employee_readonly_role.sql}，
     * 否则 {@code EmployeePermissionsTest} 会失败 —— 这是故意的。
     */
    public static final Set<String> READ_ONLY = Set.of(
        "dashboard:read",       // 仪表板
        "video:read",           // 视频列表 / 详情
        "document:read",        // 文档列表 / 详情
        "watermark:read",       // 水印溯源
        "notification:read",    // 通知
        "tag:read",             // 标签列表
        "search:read",          // 全局搜索（结果按可见性过滤）
        "playback:read",        // 播放
        "preview:read",         // 预览
        "classification:read"   // 密级字典
    );

    /**
     * 写动作关键词 —— 白名单里出现任何一个都算配置错误。
     *
     * <p>与 {@code sys_permission.action} 的取值对齐（见权限目录迁移）。
     * 之所以用"关键词"而不是"完整权限码列表"：新增权限码时，
     * 只要 action 落在这些词里，就一定不是只读。
     */
    public static final Set<String> WRITE_ACTIONS = Set.of(
        "upload", "edit", "delete", "download", "print", "share",
        "manage", "approve", "audit_export", "backup", "restore"
    );

    private EmployeePermissions() {
    }
}
