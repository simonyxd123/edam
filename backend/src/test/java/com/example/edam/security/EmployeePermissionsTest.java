package com.example.edam.security;

import com.example.edam.controller.DocumentController;
import com.example.edam.controller.PlaybackController;
import com.example.edam.controller.PreviewController;
import com.example.edam.controller.VideoController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 普通员工只读基线的回归测试。
 *
 * <p><b>为什么这类测试必须有</b>：权限是**数据**（{@code sys_role_permission} 表），
 * 不是代码。改一个角色能干什么，只要在管理界面上勾两下就变了 ——
 * 代码里没有任何东西会"编译报错"提醒你。
 *
 * <p>所以这里把三件东西钉在一起：
 * <ol>
 *   <li>{@link EmployeePermissions#READ_ONLY} —— 代码里声明的只读白名单；</li>
 *   <li>权限目录（{@code V20260830_1000__rbac_seed.sql}）—— 每个权限码的 action；</li>
 *   <li>迁移（{@code V20261105_1000__employee_readonly_role.sql}）—— 真正落到库里的授权。</li>
 * </ol>
 * 三者任何一处漂移，这里就红。
 *
 * <p>另外用反射把**写端点的 {@code @PreAuthorize}** 也钉住 ——
 * 因为"白名单里没有 upload"只有在端点本身也校验时才成立。
 */
class EmployeePermissionsTest {

    // ==================================================================
    // 资源读取
    // ==================================================================

    private static String readMigration(String name) {
        try (InputStream in = EmployeePermissionsTest.class.getClassLoader()
                .getResourceAsStream("db/migration/" + name)) {
            assertNotNull(in, "迁移文件不在 classpath 上: db/migration/" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取迁移文件失败: " + name, e);
        }
    }

    /** 权限目录：权限码 → action（read / upload / edit / delete …） */
    private static Map<String, String> permissionCatalog() {
        String sql = readMigration("V20260830_1000__rbac_seed.sql");
        // ('video:read', '查看视频', 'video', 'read', NULL),
        Pattern p = Pattern.compile(
            "\\('([A-Za-z_]+:[A-Za-z_]+)',\\s*'[^']*',\\s*'[A-Za-z_]+',\\s*'([A-Za-z_]+)'");
        Matcher m = p.matcher(sql);
        Map<String, String> catalog = new java.util.LinkedHashMap<>();
        while (m.find()) {
            catalog.put(m.group(1), m.group(2));
        }
        return catalog;
    }

    /** 从一段 SQL 文本里抽出所有 '资源:动作' 形式的权限码 */
    private static Set<String> extractCodes(String block) {
        Set<String> codes = new LinkedHashSet<>();
        Matcher m = Pattern.compile("'([A-Za-z_]+:[A-Za-z_]+)'").matcher(block);
        while (m.find()) {
            codes.add(m.group(1));
        }
        return codes;
    }

    /** 迁移里 employee 角色"要删掉的"授权块（NOT IN 列表） */
    private static Set<String> employeeDeleteBlock() {
        String sql = readMigration("V20261105_1000__employee_readonly_role.sql");
        Matcher m = Pattern.compile("AND p\\.code NOT IN \\(([^)]*)\\)", Pattern.DOTALL).matcher(sql);
        assertTrue(m.find(), "迁移里找不到 employee 的 NOT IN 授权块");
        return extractCodes(m.group(1));
    }

    /** 迁移里 employee 角色"要补上的"授权块（IN 列表，且 WHERE r.code = 'employee'） */
    private static Set<String> employeeGrantBlock() {
        String sql = readMigration("V20261105_1000__employee_readonly_role.sql");
        Matcher m = Pattern.compile(
            "JOIN sys_permission p ON p\\.code IN \\(([^)]*)\\)\\s*WHERE r\\.code = 'employee'",
            Pattern.DOTALL).matcher(sql);
        assertTrue(m.find(), "迁移里找不到 employee 的 IN 授权块");
        return extractCodes(m.group(1));
    }

    /** 迁移里 dept_manager 角色被补的授权块 */
    private static Set<String> deptManagerGrantBlock() {
        String sql = readMigration("V20261105_1000__employee_readonly_role.sql");
        Matcher m = Pattern.compile(
            "JOIN sys_permission p ON p\\.code IN \\(([^)]*)\\)\\s*WHERE r\\.code = 'dept_manager'",
            Pattern.DOTALL).matcher(sql);
        assertTrue(m.find(), "迁移里找不到 dept_manager 的 IN 授权块");
        return extractCodes(m.group(1));
    }

    // ==================================================================
    // 白名单本身
    // ==================================================================

    @Test
    @DisplayName("白名单里的每个权限码都在权限目录中存在")
    void everyAllowedCodeExistsInCatalog() {
        Map<String, String> catalog = permissionCatalog();
        assertFalse(catalog.isEmpty(), "权限目录解析失败 —— 测试本身有问题");
        for (String code : EmployeePermissions.READ_ONLY) {
            assertTrue(catalog.containsKey(code),
                "白名单里的权限码不在权限目录中: " + code
                    + "（迁移执行后 employee 会拿到一个查不到定义的授权码）");
        }
    }

    @Test
    @DisplayName("白名单里的每个权限码 action 都是 read —— 普通员工只有只读权")
    void everyAllowedCodeIsReadOnly() {
        Map<String, String> catalog = permissionCatalog();
        for (String code : EmployeePermissions.READ_ONLY) {
            String action = catalog.get(code);
            assertNotNull(action, "权限码不在目录里: " + code);
            assertFalse(EmployeePermissions.WRITE_ACTIONS.contains(action),
                "普通员工不该有写权限: " + code + "（action=" + action + "）");
            assertEquals("read", action,
                "普通员工的权限 action 必须是 read: " + code + "（实际 " + action + "）");
        }
    }

    @Test
    @DisplayName("白名单里没有历史上给 employee 的写权限")
    void writePermissionsAreNotAllowed() {
        // 这两个是 V20260830_1000 预置时给 employee 的，本次必须收掉
        assertFalse(EmployeePermissions.READ_ONLY.contains("video:upload"));
        assertFalse(EmployeePermissions.READ_ONLY.contains("document:upload"));
        assertFalse(EmployeePermissions.READ_ONLY.contains("video:delete"));
        assertFalse(EmployeePermissions.READ_ONLY.contains("document:delete"));
        assertFalse(EmployeePermissions.READ_ONLY.contains("video:share"));
        assertFalse(EmployeePermissions.READ_ONLY.contains("document:share"));
    }

    // ==================================================================
    // 白名单 ↔ 迁移的一致性
    // ==================================================================

    @Test
    @DisplayName("迁移里 employee 的授权集合 == 代码里的白名单")
    void migrationMatchesWhitelist() {
        Set<String> expected = EmployeePermissions.READ_ONLY;
        assertEquals(expected, employeeGrantBlock(),
            "迁移给 employee 补的授权与 EmployeePermissions.READ_ONLY 不一致");
        assertEquals(expected, employeeDeleteBlock(),
            "迁移保留的授权与 EmployeePermissions.READ_ONLY 不一致 —— "
                + "白名单改了但迁移没同步（或者反过来）");
    }

    @Test
    @DisplayName("迁移给 dept_manager 补齐删除权")
    void deptManagerGetsDeletePermissions() {
        Set<String> granted = deptManagerGrantBlock();
        assertTrue(granted.contains("video:delete"),
            "dept_manager 能上传却删不掉自己传的东西 —— 迁移应补 video:delete");
        assertTrue(granted.contains("document:delete"),
            "dept_manager 能上传却删不掉自己传的东西 —— 迁移应补 document:delete");
        // 经理不能顺手拿到审计导出之类的敏感权限
        assertFalse(granted.contains("audit:audit_export"));
        assertFalse(granted.contains("admin:backup"));
    }

    // ==================================================================
    // 端点侧：白名单要生效，端点自己必须校验
    // ==================================================================

    @Test
    @DisplayName("上传 / 删除端点都有 @PreAuthorize —— 白名单才拦得住")
    void writeEndpointsAreGuarded() {
        assertPreAuthorize(VideoController.class, "upload", "hasAuthority('video:upload')");
        assertPreAuthorize(VideoController.class, "delete", "hasAuthority('video:delete')");
        assertPreAuthorize(VideoController.class, "batchDelete", "hasAuthority('video:delete')");
        assertPreAuthorize(DocumentController.class, "upload", "hasAuthority('document:upload')");
        assertPreAuthorize(DocumentController.class, "delete", "hasAuthority('document:delete')");
        assertPreAuthorize(DocumentController.class, "batchDelete", "hasAuthority('document:delete')");
    }

    @Test
    @DisplayName("播放 / 预览端点要求对应的只读权限 —— 普通员工仍能看")
    void readEndpointsAreGuarded() {
        assertPreAuthorize(PlaybackController.class, "getToken", "hasAuthority('video:read')");
        assertPreAuthorize(PreviewController.class, "preview", "hasAuthority('document:read')");
        assertPreAuthorize(DocumentController.class, "preview", "hasAuthority('document:read')");
    }

    private static void assertPreAuthorize(Class<?> controller, String methodName, String expected) {
        Method found = null;
        int matches = 0;
        for (Method m : controller.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                found = m;
                matches++;
            }
        }
        assertNotNull(found, "找不到方法: " + controller.getSimpleName() + "#" + methodName);
        assertEquals(1, matches,
            controller.getSimpleName() + "#" + methodName + " 有重载 —— 测试得指明是哪一个");
        PreAuthorize ann = found.getAnnotation(PreAuthorize.class);
        assertNotNull(ann,
            controller.getSimpleName() + "#" + methodName + " 没有 @PreAuthorize —— "
                + "任何登录用户都能调用它");
        assertEquals(expected, ann.value(),
            controller.getSimpleName() + "#" + methodName + " 的权限码不对");
    }
}
