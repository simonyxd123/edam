package com.example.edam.audit;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 审计操作类型（operation_log.operation_type 的取值集合）。
 *
 * <p><b>为什么要有这一份统一清单</b>：2026-11-03 之前，前端 Audit.vue 的筛选下拉写的是
 * {@code ['login','logout','view','download','upload','delete','permission_change','config_change']}，
 * 而后端实际写进库的是 {@code create / update / delete / preview / create_role / assign_roles …} ——
 * <b>两套名字几乎对不上</b>。于是审计页上的"操作类型"筛选框形同虚设：
 * 选了"upload"查不到任何东西，选了"view"也查不到。
 *
 * <p>这份清单把前后端拉到同一个事实上：<b>后端写什么，前端就有什么</b>。
 * 前端 {@code web/src/constants/audit.ts} 是同一份清单的镜像，改一处必须改两处
 * （有单测 {@code AuditOpTypeParity.spec.ts} 锁住）。
 *
 * <p><b>关于历史别名</b>：库里已经有一批旧值（{@code create_role}、{@code upload}、
 * {@code view}…）。它们不会被改写（改写等于篡改历史记录），但<b>筛选时</b>
 * 要能按新的规范名查到它们 —— 见 {@link #storedValuesFor(String)}。
 *
 * <p>对应方案书 <b>§7.1 核心数据表</b>（审计与溯源：播放日志表 play_log、操作日志表 operation_log）
 * 与 <b>§14.3 STRIDE / Repudiation（否认）</b>（"员工否认操作行为 → 操作日志全审计"）。
 */
public final class AuditOpType {

    private AuditOpType() {
    }

    // ------------------------------------------------------------------
    // 登录 / 会话
    // ------------------------------------------------------------------

    /** 登录（含失败，失败与否看 result 列） */
    public static final String LOGIN = "login";
    /** 登出 */
    public static final String LOGOUT = "logout";
    /** 刷新 access_token */
    public static final String TOKEN_REFRESH = "token_refresh";
    /** SSO 单点登录交换 */
    public static final String SSO_LOGIN = "sso_login";
    /** 自助改密码 */
    public static final String PASSWORD_CHANGE = "password_change";
    /** 改个人资料（邮箱/手机/姓名） */
    public static final String PROFILE_UPDATE = "profile_update";

    // ------------------------------------------------------------------
    // 读取（"谁看了什么" —— 防泄密系统里最要紧的一类）
    // ------------------------------------------------------------------

    /** 列表查询 */
    public static final String LIST = "list";
    /** 查看详情 */
    public static final String DETAIL = "detail";
    /** 搜索 */
    public static final String SEARCH = "search";
    /** 播放（签发播放凭据 / 播放心跳） */
    public static final String PLAY = "play";
    /** 预览（文档预览、视频预览） */
    public static final String PREVIEW = "preview";
    /** 下载 */
    public static final String DOWNLOAD = "download";

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    /** 新增 / 上传 */
    public static final String CREATE = "create";
    /** 修改 */
    public static final String UPDATE = "update";
    /** 删除 */
    public static final String DELETE = "delete";
    /** 批量删除 */
    public static final String BATCH_DELETE = "batch_delete";

    // ------------------------------------------------------------------
    // 授权
    // ------------------------------------------------------------------

    /** 授予权限 */
    public static final String GRANT_PERMISSION = "grant_permission";
    /** 撤销权限 */
    public static final String REVOKE_PERMISSION = "revoke_permission";

    // ------------------------------------------------------------------
    // 其它
    // ------------------------------------------------------------------

    /** 配置类变更（标签、Webhook、通知偏好、水印策略…） */
    public static final String CONFIG_CHANGE = "config_change";
    /** 导出（审计导出等） */
    public static final String EXPORT = "export";
    /**
     * 访问被拒绝（请求在过滤器链里就被 401/403 挡下，压根没进到具体接口）。
     *
     * <p>由 {@code AuditAccessFilter} 记录 —— 切面包的是控制器方法，
     * 够不着"没进控制器就被拒"的那一类请求。
     */
    public static final String ACCESS_DENIED = "access_denied";

    /**
     * 全部规范操作类型（前端下拉框用这个顺序）。
     */
    public static final List<String> ALL = List.of(
        LOGIN, LOGOUT, TOKEN_REFRESH, SSO_LOGIN, PASSWORD_CHANGE, PROFILE_UPDATE,
        LIST, DETAIL, SEARCH, PLAY, PREVIEW, DOWNLOAD,
        CREATE, UPDATE, DELETE, BATCH_DELETE,
        GRANT_PERMISSION, REVOKE_PERMISSION,
        CONFIG_CHANGE, EXPORT,
        ACCESS_DENIED
    );

    /**
     * 历史别名 → 规范名的归并表。
     *
     * <p>键是**库里真实存在过的旧值**，值是规范名。
     */
    private static final Map<String, String> LEGACY_ALIAS = Map.ofEntries(
        Map.entry("create_role", CREATE),
        Map.entry("update_role", UPDATE),
        Map.entry("delete_role", DELETE),
        Map.entry("assign_permissions", GRANT_PERMISSION),
        Map.entry("assign_roles", GRANT_PERMISSION),
        Map.entry("reset_password", UPDATE),
        Map.entry("revoke_keys", UPDATE),
        Map.entry("permission_change", GRANT_PERMISSION),
        Map.entry("view", DETAIL),
        Map.entry("upload", CREATE),
        Map.entry("preview", PREVIEW),
        Map.entry("download", DOWNLOAD)
    );

    /**
     * 规范名 → 库里所有等价的历史值（含它自己）。
     *
     * <p>筛选 {@code create} 时要能同时查到 {@code create} / {@code upload} / {@code create_role}
     * 三种存法的记录 —— 否则老数据在审计页上"凭空消失"，
     * 让人误以为那些操作没发生过。
     *
     * @param canonical 规范操作名；null / 空白返回空集合
     */
    public static Set<String> storedValuesFor(String canonical) {
        Set<String> values = new LinkedHashSet<>();
        if (canonical == null || canonical.isBlank()) {
            return values;
        }
        values.add(canonical);
        for (Map.Entry<String, String> e : LEGACY_ALIAS.entrySet()) {
            if (e.getValue().equals(canonical)) {
                values.add(e.getKey());
            }
        }
        return values;
    }

    /**
     * 历史别名归并：把库里存的老名字映射成规范名。
     *
     * <p><b>只用于展示</b>（前端表格里显示得让人看得懂），
     * <b>不改写数据库</b> —— 历史记录不该被后来的规范"修饰"。
     *
     * @return 规范名；本来就是规范名则原样返回
     */
    public static String normalize(String stored) {
        if (stored == null) {
            return null;
        }
        return LEGACY_ALIAS.getOrDefault(stored, stored);
    }

    /**
     * 历史别名表（供前端做同样的归并展示）。
     */
    public static Map<String, String> legacyAlias() {
        return LEGACY_ALIAS;
    }
}
