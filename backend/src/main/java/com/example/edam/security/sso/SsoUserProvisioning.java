package com.example.edam.security.sso;

import com.example.edam.model.SysUser;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

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
    private final StringRedisTemplate redisTemplate;
    private final JwtTokenProvider jwtTokenProvider;

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

        // 1. 创建 session_id
        String sessionId = UUID.randomUUID().toString();

        // 2. 创建 JWT Token
        List<String> roles = userInfo.getRoles() != null ? userInfo.getRoles()
            : List.of("ROLE_" + defaultRoleCode.toUpperCase());
        String accessToken = jwtTokenProvider.createAccessToken(
            user.getId(), sessionId, roles);
        String refreshToken = jwtTokenProvider.createRefreshToken();

        // 3. 存储 session（Redis）
        String redisKey = "refresh:" + refreshToken;
        redisTemplate.opsForValue().set(redisKey,
            String.valueOf(user.getId()),
            Duration.ofDays(7));

        SsoLoginResult result = new SsoLoginResult();
        result.setAccessToken(accessToken);
        result.setRefreshToken(refreshToken);
        result.setUserId(user.getId());
        result.setSessionId(sessionId);
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