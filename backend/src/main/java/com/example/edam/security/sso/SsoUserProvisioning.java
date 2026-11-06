package com.example.edam.security.sso;

import com.example.edam.model.SysUser;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.security.LoginAttemptService;
import com.example.edam.service.TokenIssuanceService;
import com.example.edam.service.UserRoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * SSO 用户自动开通（JIT Provisioning）（v3.3 W-4.4）
 *
 * 流程：
 * 1. 接收 IdP 返回的用户信息
 * 2. 查询 EDAM 数据库是否存在
 * 3. 不存在 → 自动创建（Just-In-Time）
 * 4. 存在 → 更新属性（姓名、邮箱、角色）
 * 5. 离职 / IdP 禁用 → 同步禁用 EDAM 账号
 * 6. 创建 EDAM Session + JWT Token
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SsoUserProvisioning {

    private final SysUserRepository userRepository;
    private final UserRoleService userRoleService;
    private final LoginAttemptService loginAttemptService;
    private final TokenIssuanceService tokenIssuanceService;

    @Value("${edam.sso.default-dept-code:external}")
    private String defaultDeptCode;

    @Value("${edam.sso.default-role-code:employee}")
    private String defaultRoleCode;

    /**
     * JIT Provisioning（自动开通/更新/禁用）
     */
    @Transactional
    public void provision(SsoUserInfo userInfo) {
        SysUser existing = userRepository.findByEmployeeNo(userInfo.getEmployeeNo());

        if (existing == null) {
            // 新用户 → 自动开通
            SysUser newUser = createNewUser(userInfo);
            // 必须在 RBAC 表里真的给一个角色，否则签发 JWT 时只能拿到兜底角色。
            // 外部身份源的用户同样受 EDAM 权限目录约束。
            userRoleService.grantRoleByCode(newUser.getId(), defaultRoleCode);
            userInfo.setNewlyProvisioned(true);
            log.info("sso_user_provisioned user_id={} employee_no={} provider={}",
                newUser.getId(), newUser.getEmployeeNo(), userInfo.getProviderId());
        } else {
            // 已存在 → 更新属性
            updateExistingUser(existing, userInfo);
            userInfo.setNewlyProvisioned(false);
            log.info("sso_user_updated user_id={} employee_no={}",
                existing.getId(), existing.getEmployeeNo());
        }

        // 检查是否被 IdP 禁用
        if (userInfo.isDisabled()) {
            disableUser(userInfo.getEmployeeNo());
        }
    }

    /**
     * 创建 EDAM Session + JWT Token
     */
    public SsoLoginResult createSession(SsoUserInfo userInfo) {
        SysUser user = userRepository.findByEmployeeNo(userInfo.getEmployeeNo());
        if (user == null) {
            throw new IllegalArgumentException("User not provisioned: " + userInfo.getEmployeeNo());
        }

        // 1. 账号状态检查（锁定 / 禁用）—— 与密码登录、WebAuthn 登录同一套判定。
        //    SSO 用户被 IdP 标记离职后会被 disableUser() 置为 status=2，
        //    这里必须挡住，否则离职账号照样能凭一张 SSO 登录票据换到有效 JWT。
        loginAttemptService.assertLoginAllowed(user);

        // 2. 签发 JWT —— 角色与权限码一律从 RBAC 表读。
        //    此前这里写死了三条最小权限（dashboard:read / video:read / document:read），
        //    等于 SSO 登录绕过 RBAC：管理员用 SSO 登录只能当普通员工。
        //    IdP 传来的 roles 只是 IdP 侧的命名，与 EDAM 的权限目录不是一回事，
        //    因此不直接写进 JWT —— EDAM 的授权只认 sys_role / sys_permission。
        TokenIssuanceService.IssuedTokens tokens = tokenIssuanceService.issue(user);

        loginAttemptService.recordSuccess(user);

        SsoLoginResult result = new SsoLoginResult();
        result.setAccessToken(tokens.getAccessToken());
        result.setRefreshToken(tokens.getRefreshToken());
        result.setUserId(user.getId());
        result.setSessionId(tokens.getSessionId());
        return result;
    }

    /**
     * 创建新用户
     */
    private SysUser createNewUser(SsoUserInfo userInfo) {
        SysUser user = new SysUser();
        user.setUsername(userInfo.getEmployeeNo());
        user.setEmployeeNo(userInfo.getEmployeeNo());
        user.setRealName(userInfo.getRealName());
        user.setEmail(userInfo.getEmail());
        user.setPhone(userInfo.getPhone());
        user.setDeptId(resolveDeptId(defaultDeptCode));
        user.setStatus(1);  // active
        user.setMfaEnabled(0);
        user.setFailedLoginCount(0);
        user.setPasswordHash("SSO_NO_PASSWORD");  // SSO 用户无本地密码
        user.setPasswordChangedAt(OffsetDateTime.now());
        user.setLastLoginAt(OffsetDateTime.now());
        userRepository.insert(user);
        return user;
    }

    /**
     * 更新已存在用户
     */
    private void updateExistingUser(SysUser user, SsoUserInfo userInfo) {
        if (userInfo.getRealName() != null) user.setRealName(userInfo.getRealName());
        if (userInfo.getEmail() != null) user.setEmail(userInfo.getEmail());
        if (userInfo.getPhone() != null) user.setPhone(userInfo.getPhone());
        if (userInfo.getDepartment() != null) {
            user.setDeptId(resolveDeptId(userInfo.getDepartment()));
        }
        user.setLastLoginAt(OffsetDateTime.now());
        userRepository.updateById(user);
    }

    /**
     * 禁用用户（IdP 离职同步）
     */
    private void disableUser(String employeeNo) {
        SysUser user = userRepository.findByEmployeeNo(employeeNo);
        if (user == null) return;
        user.setStatus(2);  // disabled
        userRepository.updateById(user);
        log.warn("sso_user_disabled employee_no={} reason=idp_status_change", employeeNo);
    }

    /**
     * 解析部门 ID（按部门 code）
     */
    private Long resolveDeptId(String deptCode) {
        // 实际生产：sys_dept 表查询
        // 简化实现：返回固定 dept_id（外部用户）
        return 1L;
    }

    /**
     * SSO 登录结果
     */
    @lombok.Data
    public static class SsoLoginResult {
        private String accessToken;
        private String refreshToken;
        private Long userId;
        private String sessionId;
    }
}