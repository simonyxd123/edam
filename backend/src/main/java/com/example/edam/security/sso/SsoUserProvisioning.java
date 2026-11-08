package com.example.edam.security.sso;

import com.example.edam.model.SysDept;
import com.example.edam.model.SysUser;
import com.example.edam.repository.SysDeptRepository;
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
    private final SysDeptRepository sysDeptRepository;
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
        // 工号是 SSO 用户的唯一标识，也是 sys_user 的 NOT NULL 列。
        // IdP 没映射这个属性时，原实现会拿 null 去查 → 查不到 → 走"新建用户"分支
        // → 往 NOT NULL 列里插 null → **500**，而且错误信息完全指不到根因
        // （看起来像"数据库炸了"）。这里提前拦下来，给出能看懂的消息。
        if (userInfo.getEmployeeNo() == null || userInfo.getEmployeeNo().isBlank()) {
            throw new IllegalArgumentException(
                "IdP 未返回工号（employee_no）—— 请检查 SSO 的属性映射配置");
        }

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
        // 新用户：优先用 IdP 给的部门，对不上号才落到兜底部门
        Long deptId = resolveDeptIdByName(userInfo.getDepartment());
        user.setDeptId(deptId != null ? deptId : resolveDefaultDeptId());
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
            // **只在能对上号的时候才改部门**。
            // 原实现无条件 `user.setDeptId(resolveDeptId(...))`，而
            // resolveDeptId 恒返回 1L ⇒ 每登一次 SSO 就把用户的部门
            // 重置成「总公司」。
            // 后果不只是显示难看：
            //   ① 密级基线按**部门名**判定（法务 / 审计 / 人力 → L3），
            //      「总公司」一个都不命中 ⇒ 用户的基线被降下来，
            //      客户端再声明 L1 就能把资产声明成公开；
            //   ② L3「同部门可见」按部门圈人 ⇒ 可见范围也变了。
            // 对不上号就**保持原部门不动**，让管理员去改 ——
            // 宁可用旧部门信息，也不能让一次登录把权限基线改小。
            Long resolved = resolveDeptIdByName(userInfo.getDepartment());
            if (resolved != null) {
                user.setDeptId(resolved);
            } else {
                log.warn("sso_department_unresolved, employee_no={}, department={} —— 保持原部门不变",
                    user.getEmployeeNo(), userInfo.getDepartment());
            }
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
     * 解析部门 ID（按部门**名称**精确查 sys_dept）。
     *
     * <p>查不到返回 {@code null} —— 调用方据此决定"保持原部门"还是"用默认部门"，
     * **不能**像原来那样无脑返回 1L。
     */
    private Long resolveDeptIdByName(String deptName) {
        if (deptName == null || deptName.isBlank()) {
            return null;
        }
        SysDept dept = sysDeptRepository.findByName(deptName.trim());
        return dept != null ? dept.getId() : null;
    }

    /**
     * 兜底部门：优先按配置的 {@code edam.sso.default-dept-code} 查名字，
     * 查不到就用 {@code sys_dept.id = 1}（总公司，迁移
     * {@code V20260830_3000__seed_org_data.sql} 必然插入）。
     *
     * <p>这个配置此前**根本没被用过** —— {@code resolveDeptId} 忽略了自己的入参，
     * 恒返回 1L。现在它真的是"兜底部门"。
     */
    private Long resolveDefaultDeptId() {
        if (defaultDeptCode != null && !defaultDeptCode.isBlank()) {
            Long byName = resolveDeptIdByName(defaultDeptCode);
            if (byName != null) {
                return byName;
            }
        }
        SysDept root = sysDeptRepository.findById(1L);
        return root != null ? root.getId() : 1L;
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