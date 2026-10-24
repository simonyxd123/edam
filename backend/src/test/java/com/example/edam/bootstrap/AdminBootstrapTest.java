package com.example.edam.bootstrap;

import com.example.edam.model.SysRole;
import com.example.edam.model.SysUser;
import com.example.edam.model.SysUserRole;
import com.example.edam.repository.SysRoleRepository;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.repository.SysUserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link AdminBootstrap} 单测。
 *
 * <p>这个类出过**线上级故障**：插入初始管理员失败时只记 error 日志、
 * 不阻断启动，于是服务正常但登不进去（前端只显示"工号或密码错误"，
 * 数据库里 sys_user 一条都没有）。这两个用例锁住关键行为：
 * <ol>
 *   <li>插入时**必须给 dept_id 一个合法值**（哪怕列可空也要兜底）——
 *       否则遇到 NOT NULL 的旧 schema 又会静默失败</li>
 *   <li>失败时状态必须可见（{@code bootstrapStatus}），
 *       否则排查时完全没有线索</li>
 * </ol>
 */
class AdminBootstrapTest {

    private SysUserRepository userRepository;
    private SysRoleRepository roleRepository;
    private SysUserRoleRepository userRoleRepository;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        userRepository = mock(SysUserRepository.class);
        roleRepository = mock(SysRoleRepository.class);
        userRoleRepository = mock(SysUserRoleRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
    }

    private AdminBootstrap newBootstrap(String activeProfile, String adminPassword,
                                       boolean createDefaultAdmin, String deptIdRaw) {
        AdminBootstrap b = new AdminBootstrap(
            userRepository, roleRepository, userRoleRepository,
            new BCryptPasswordEncoder(12), jdbcTemplate);
        setField(b, "activeProfile", activeProfile);
        setField(b, "adminPassword", adminPassword);
        setField(b, "createDefaultAdmin", createDefaultAdmin);
        setField(b, "adminDeptIdRaw", deptIdRaw);
        return b;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("反射设置字段失败: " + name, e);
        }
    }

    @Test
    @DisplayName("dev profile：建出的管理员有部门 id（兜底取 sys_dept 第一条）")
    void devProfileCreatesAdminWithDeptId() {
        when(userRepository.findByEmployeeNo("E000001")).thenReturn(null);
        // sys_dept 里有一条
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
            .thenReturn(List.of(4L));
        SysRole adminRole = new SysRole();
        adminRole.setCode("admin");
        when(roleRepository.selectList(any())).thenReturn(List.of(adminRole));

        AdminBootstrap b = newBootstrap("dev", "", false, "");
        b.run();

        // 关键断言：插入的 admin 必须带 dept_id
        org.mockito.ArgumentCaptor<SysUser> captor =
            org.mockito.ArgumentCaptor.forClass(SysUser.class);
        verify(userRepository).insert(captor.capture());
        SysUser created = captor.getValue();
        assertNotNull(created.getDeptId(), "初始管理员必须带 dept_id —— 列可空也别留 null，"
            + "遇到旧 schema（NOT NULL）会静默插入失败");
        assertEquals(4L, created.getDeptId());
        assertEquals("E000001", created.getEmployeeNo());
        assertEquals("admin", created.getUsername());
        assertNotNull(created.getPasswordHash());
        assertEquals("ok", AdminBootstrap.getBootstrapStatus());
    }

    @Test
    @DisplayName("显式配置的部门 id 优先于自动选取")
    void explicitDeptIdWins() {
        when(userRepository.findByEmployeeNo("E000001")).thenReturn(null);
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
            .thenReturn(List.of(4L));
        SysRole adminRole = new SysRole();
        adminRole.setCode("admin");
        when(roleRepository.selectList(any())).thenReturn(List.of(adminRole));

        AdminBootstrap b = newBootstrap("dev", "", false, "7");
        b.run();

        org.mockito.ArgumentCaptor<SysUser> captor =
            org.mockito.ArgumentCaptor.forClass(SysUser.class);
        verify(userRepository).insert(captor.capture());
        assertEquals(7L, captor.getValue().getDeptId());
    }

    @Test
    @DisplayName("dev profile 插入失败：fail fast（服务起不来是明确的，不是神秘的登录失败）")
    void devFailureFailsFast() {
        when(userRepository.findByEmployeeNo("E000001")).thenReturn(null);
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
            .thenReturn(List.of(4L));
        SysRole adminRole = new SysRole();
        adminRole.setCode("admin");
        when(roleRepository.selectList(any())).thenReturn(List.of(adminRole));
        doThrow(new RuntimeException("Field 'dept_id' doesn't have a default value"))
            .when(userRepository).insert(any(SysUser.class));

        AdminBootstrap b = newBootstrap("dev", "", false, "");
        // dev 下没有初始管理员 = 环境不可用（登录页都进不去）→ 必须明确失败
        assertThrows(IllegalStateException.class, () -> b.run(),
            "dev profile 下自举失败必须 fail fast —— 静默降级会让症状变成"
                + "'服务正常但登不进去'，排查成本极高");
        // 状态仍要可见
        assertEquals("failed", AdminBootstrap.getBootstrapStatus());
        assertNotNull(AdminBootstrap.getBootstrapError());
        assertTrue(AdminBootstrap.getBootstrapError().contains("dept_id"),
            "错误信息应包含原始原因");
    }

    @Test
    @DisplayName("production profile 插入失败：降级不阻断（服务要能起来）")
    void productionFailureDegradesGracefully() {
        when(userRepository.findByEmployeeNo("E000001")).thenReturn(null);
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
            .thenReturn(List.of(4L));
        SysRole adminRole = new SysRole();
        adminRole.setCode("admin");
        when(roleRepository.selectList(any())).thenReturn(List.of(adminRole));
        doThrow(new RuntimeException("boom"))
            .when(userRepository).insert(any(SysUser.class));

        AdminBootstrap b = newBootstrap("production", "SomeStrongPassword", true, "");
        // production：不抛异常，服务照常启动
        assertDoesNotThrow(() -> b.run());
        // 但状态必须可见（运维能从 /health/ready 看到）
        assertEquals("failed", AdminBootstrap.getBootstrapStatus());
        assertNotNull(AdminBootstrap.getBootstrapError());
    }

    @Test
    @DisplayName("production profile 且未配密码：不建任何账号")
    void productionWithoutPasswordCreatesNothing() {
        when(userRepository.findByEmployeeNo("E000001")).thenReturn(null);

        AdminBootstrap b = newBootstrap("production", "", false, "");
        b.run();

        verify(userRepository, never()).insert(any(SysUser.class));
        assertEquals("ok", AdminBootstrap.getBootstrapStatus());
    }
}
