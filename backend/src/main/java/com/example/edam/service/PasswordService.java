package com.example.edam.service;

import com.example.edam.model.SysPasswordHistory;
import com.example.edam.model.SysUser;
import com.example.edam.repository.SysPasswordHistoryRepository;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.security.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 密码管理服务（v3.3 W-1 G-5）
 *
 * 职责：
 * - 修改密码（验证策略 + 历史记录）
 * - 重置密码（管理员）
 * - 检查密码是否需要轮转
 * - 清理过期密码历史
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordService {

    private final SysUserRepository userRepository;
    private final SysPasswordHistoryRepository historyRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy policy;

    /**
     * 用户修改密码
     *
     * @param userId      用户 ID
     * @param oldPassword 旧密码（明文）
     * @param newPassword 新密码（明文）
     */
    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        SysUser user = userRepository.findById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }

        // 1. 校验旧密码
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("旧密码错误");
        }

        // 2. 验证新密码策略
        PasswordPolicy.ValidationResult result = policy.validate(newPassword);
        if (!result.isValid()) {
            throw new IllegalArgumentException(result.getReason());
        }

        // 3. 校验历史密码（不能与最近 N 次相同）
        List<String> recentHashes = historyRepository.findRecentHashes(userId, policy.getHistoryCount());
        String newHash = passwordEncoder.encode(newPassword);
        for (String oldHash : recentHashes) {
            if (passwordEncoder.matches(newPassword, oldHash)) {
                throw new IllegalArgumentException(
                    "新密码不能与最近 " + policy.getHistoryCount() + " 次密码相同");
            }
        }

        // 4. 更新密码
        user.setPasswordHash(newHash);
        user.setPasswordChangedAt(OffsetDateTime.now());
        user.setMustChangePassword(false);
        user.setFailedLoginCount(0);  // 重置失败计数
        userRepository.updateById(user);

        // 5. 记录密码历史
        SysPasswordHistory history = new SysPasswordHistory();
        history.setUserId(userId);
        history.setPasswordHash(newHash);
        history.setChangedBy(userId);
        history.setChangeReason("rotation");
        historyRepository.insert(history);

        log.info("password_changed user_id={} rotation_days={}",
            userId, policy.getRotationDays());
    }

    /**
     * 管理员重置密码。
     *
     * <p><b>顺带解锁"被自动锁定"的账号</b>（2026-11-14 代码审计第六轮）：
     * 此前这里只改密码，<b>完全不动锁定状态</b> ⇒
     * 一个连续输错 5 次被自动锁住的账号，管理员给它重置了密码，
     * 它<b>依然登不进去</b>（{@code assertLoginAllowed} 照旧抛
     * {@code AccountLockedException}）—— 管理员看到"重置成功"，
     * 用户却还是进不去，只能再来找管理员。
     *
     * <p>解的是**自动锁定**（{@code locked_until} 非空 = 有到期时间）。
     * 管理员<b>手动</b>锁定的账号（{@code locked_until} 为空 = 不自动解锁）
     * <b>不在这里解锁</b> —— 那是管理员刻意为之（疑似泄密调查中等），
     * 要放人请走 {@code PUT /users/{id}} 把状态改回 active。
     */
    @Transactional
    public String resetPassword(Long userId, Long operatorId) {
        SysUser user = userRepository.findById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }

        // 生成临时密码（满足策略）
        String tempPassword = generateTempPassword();
        String newHash = passwordEncoder.encode(tempPassword);

        user.setPasswordHash(newHash);
        user.setPasswordChangedAt(OffsetDateTime.now());
        user.setMustChangePassword(true);  // 强制下次登录修改

        // 解锁（仅自动锁定）+ 失败计数归零
        unlockAutoLocked(user);

        userRepository.updateById(user);

        SysPasswordHistory history = new SysPasswordHistory();
        history.setUserId(userId);
        history.setPasswordHash(newHash);
        history.setChangedBy(operatorId);
        history.setChangeReason("reset");
        historyRepository.insert(history);

        log.warn("password_reset user_id={} operator_id={} temp_password_provided=true",
            userId, operatorId);

        return tempPassword;
    }

    /**
     * 解锁"被自动锁定"的账号，并把失败计数归零。
     *
     * <p><b>只解自动锁定</b>：{@code locked_until != null} 说明这是"连错几次"
     * 触发的、有到期时间的自动锁定。{@code locked_until == null} 说明是管理员
     * 手动锁的（第五轮引入的语义：{@code NULL = 不自动解锁}）—— 那种不动。
     *
     * <p>{@code status == 2}（禁用）也不动 —— 禁用是管理员的决定，不是锁定的副作用。
     */
    private void unlockAutoLocked(SysUser user) {
        if (user.getStatus() != null && user.getStatus() == 3 && user.getLockedUntil() != null) {
            user.setStatus(1);
            log.info("account_unlocked_by_password_reset, user_id={}", user.getId());
        }
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
    }

    /**
     * 检查用户密码是否需要轮转
     */
    public boolean needsRotation(Long userId) {
        SysUser user = userRepository.findById(userId);
        if (user == null) return false;
        return policy.needsRotation(user.getPasswordChangedAt());
    }

    /**
     * 生成满足策略的临时密码
     */
    private String generateTempPassword() {
        // 实际生产：从第三方密码管理器或强随机数生成
        // 简化版：固定前缀 + 16 位随机
        java.security.SecureRandom random = new java.security.SecureRandom();
        String chars = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789!@#$%^&*";
        StringBuilder sb = new StringBuilder("Tmp#");
        for (int i = 0; i < 16; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}