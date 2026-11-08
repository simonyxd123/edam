package com.example.edam.security.sso;

import com.example.edam.model.SysDept;
import com.example.edam.model.SysUser;
import com.example.edam.repository.SysDeptRepository;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.security.LoginAttemptService;
import com.example.edam.service.TokenIssuanceService;
import com.example.edam.service.UserRoleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SSO 用户开通的回归测试（2026-11-08 审计）。
 *
 * <p>盯两件事：
 * <ol>
 *   <li><b>IdP 没给工号</b> ⇒ 必须给出能看懂的错，而不是往 NOT NULL 列里插 null
 *       变成 500。</li>
 *   <li><b>部门不能被登录动作改小</b> —— 原实现里 {@code resolveDeptId}
 *       恒返回 1L，于是每登一次 SSO 就把用户部门重置成「总公司」，
 *       而密级基线是按**部门名**判定的（法务 / 审计 / 人力 → L3），
 *       「总公司」一个都不命中 ⇒ 用户的密级基线被降下来。</li>
 * </ol>
 *
 * <p>方案书 5.6 密级与可见性 / 13.1 会话与密钥生命周期。
 */
class SsoUserProvisioningTest {

    private static final String EMP_NO = "E000042";

    private SysUserRepository userRepository;
    private SysDeptRepository sysDeptRepository;
    private UserRoleService userRoleService;
    private SsoUserProvisioning provisioning;

    @BeforeEach
    void setUp() {
        userRepository = mock(SysUserRepository.class);
        sysDeptRepository = mock(SysDeptRepository.class);
        userRoleService = mock(UserRoleService.class);
        provisioning = new SsoUserProvisioning(
            userRepository,
            sysDeptRepository,
            userRoleService,
            mock(LoginAttemptService.class),
            mock(TokenIssuanceService.class));
        ReflectionTestUtils.setField(provisioning, "defaultDeptCode", "总公司");
        ReflectionTestUtils.setField(provisioning, "defaultRoleCode", "employee");
    }

    private SsoUserInfo info(String employeeNo, String department) {
        SsoUserInfo i = new SsoUserInfo();
        i.setProviderId("oidc-1");
        i.setEmployeeNo(employeeNo);
        i.setRealName("张三");
        i.setEmail("zhangsan@example.com");
        i.setDepartment(department);
        return i;
    }

    private SysDept dept(long id, String name) {
        SysDept d = new SysDept();
        d.setId(id);
        d.setName(name);
        return d;
    }

    @Test
    @DisplayName("IdP 没映射工号 ⇒ 明确的报错，而不是往 NOT NULL 列插 null 变 500")
    void missingEmployeeNoIsRejected() {
        SsoUserInfo i = info(null, "安全部");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> provisioning.provision(i));
        assertTrue(ex.getMessage().contains("工号"), "错误信息要指向根因，实际: " + ex.getMessage());

        verify(userRepository, never()).insert(any(SysUser.class));
    }

    @Test
    @DisplayName("IdP 给了空串工号 ⇒ 同样拒绝")
    void blankEmployeeNoIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> provisioning.provision(info("   ", "安全部")));
        verify(userRepository, never()).insert(any(SysUser.class));
    }

    @Test
    @DisplayName("老用户：SSO 给的部门对得上号 ⇒ 部门跟着更新")
    void existingUserDepartmentIsUpdatedWhenResolvable() {
        SysUser existing = new SysUser();
        existing.setId(7L);
        existing.setEmployeeNo(EMP_NO);
        existing.setDeptId(4L);   // 安全部
        when(userRepository.findByEmployeeNo(EMP_NO)).thenReturn(existing);
        when(sysDeptRepository.findByName("法务部")).thenReturn(dept(6L, "法务部"));

        provisioning.provision(info(EMP_NO, "法务部"));

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userRepository).updateById(captor.capture());
        assertEquals(6L, captor.getValue().getDeptId());
    }

    @Test
    @DisplayName("老用户：SSO 给的部门对不上号 ⇒ 保持原部门，不能被改成总公司")
    void existingUserKeepsDepartmentWhenUnresolvable() {
        SysUser existing = new SysUser();
        existing.setId(7L);
        existing.setEmployeeNo(EMP_NO);
        existing.setDeptId(4L);   // 安全部
        when(userRepository.findByEmployeeNo(EMP_NO)).thenReturn(existing);
        // IdP 的部门名我们 sys_dept 里没有 → findByName 返回 null

        provisioning.provision(info(EMP_NO, "某个不存在的部门"));

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userRepository).updateById(captor.capture());
        // 关键回归点：部门不能被重置成 1（总公司）——
        // 那会让密级基线（按部门名判定）掉下来
        assertEquals(4L, captor.getValue().getDeptId());
    }

    @Test
    @DisplayName("新用户：SSO 给了能对上的部门 ⇒ 用它")
    void newUserUsesIdpDepartment() {
        when(userRepository.findByEmployeeNo(EMP_NO)).thenReturn(null);
        when(sysDeptRepository.findByName("安全部")).thenReturn(dept(4L, "安全部"));

        provisioning.provision(info(EMP_NO, "安全部"));

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userRepository).insert(captor.capture());
        assertEquals(4L, captor.getValue().getDeptId());
        verify(userRoleService).grantRoleByCode(any(), anyString());
    }

    @Test
    @DisplayName("新用户：SSO 的部门对不上 ⇒ 落到兜底部门（默认部门 / 总公司）")
    void newUserFallsBackToDefaultDepartment() {
        when(userRepository.findByEmployeeNo(EMP_NO)).thenReturn(null);
        when(sysDeptRepository.findByName("总公司")).thenReturn(dept(1L, "总公司"));
        when(sysDeptRepository.findById(1L)).thenReturn(dept(1L, "总公司"));

        provisioning.provision(info(EMP_NO, "某个不存在的部门"));

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userRepository).insert(captor.capture());
        assertEquals(1L, captor.getValue().getDeptId());
    }
}
