package com.example.edam.security;

import com.example.edam.exception.AccountLockedException;
import com.example.edam.model.SysUser;
import com.example.edam.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录失败计数的独立事务服务。
 *
 * <p><b>为什么必须独立事务</b>：{@code AuthService.login()} 带
 * {@code @Transactional}，而密码错误时它会
 * {@code handleFailedLogin()} → {@code throw ResourceNotFoundException}。
 * Spring 对 RuntimeException 默认回滚 —— 于是
 * {@code userRepository.updateById(user)} 写入的
 * {@code failed_login_count} <b>被同一个事务回滚掉了</b>，
 * 计数永远写不进 DB，"连续 5 次锁定账号"的防护一次都不会触发。
 *
 * <p>用 {@code REQUIRES_NEW} 起一个独立事务，失败计数就能在
 * 外层回滚之外真正落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    /** 连续失败达到该次数即锁定账号 */
    public static final int MAX_FAILED_ATTEMPTS = 5;

    /** 账号锁定时长（分钟） */
    public static final int LOCK_DURATION_MINUTES = 30;

    private final SysUserRepository userRepository;

    /**
     * 登录准入检查 —— 账号被锁定 / 禁用时拒绝登录。
     *
     * <p>此前这个检查只写在 {@code AuthService.login()}（密码登录）里，
     * 于是 <b>WebAuthn 登录</b>与 <b>SSO 登录</b>两条路径完全绕过了它：
     * 一个已锁定或已离职的账号，照样能用安全密钥换到一张有效的 JWT。
     *
     * <p>现在三条登录路径统一走这里。
     *
     * @throws AccountLockedException 账号锁定中
     * @throws IllegalArgumentException 账号已禁用
     */
    public void assertLoginAllowed(SysUser user) {
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        Integer status = user.getStatus();
        if (status != null && status == 3) {
            throw new AccountLockedException(LOCK_DURATION_MINUTES * 60L);
        }
        if (status != null && status == 2) {
            throw new IllegalArgumentException("账号已禁用，请联系管理员");
        }
    }

    /**
     * 记录一次登录失败（独立事务，不受外层回滚影响）。
     *
     * @param user          用户实体
     * @param maxAttempts   达到该次数即锁定
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(SysUser user, int maxAttempts) {
        try {
            int failedCount = (user.getFailedLoginCount() == null ? 0 : user.getFailedLoginCount()) + 1;
            user.setFailedLoginCount(failedCount);
            if (failedCount >= maxAttempts) {
                user.setStatus(3);
                log.warn("账号锁定: employee_no={} failed_count={}",
                    user.getEmployeeNo(), failedCount);
            }
            userRepository.updateById(user);
        } catch (Exception e) {
            // 计数写不进去也要让登录继续失败 —— 但必须留下痕迹，
            // 原来这里是裸 except: pass，完全静默
            log.error("login_failure_record_failed, employee_no={}", user.getEmployeeNo(), e);
        }
    }

    /**
     * 登录成功后清零失败计数（独立事务）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(SysUser user) {
        try {
            user.setFailedLoginCount(0);
            userRepository.updateById(user);
        } catch (Exception e) {
            log.error("login_success_record_failed, employee_no={}", user.getEmployeeNo(), e);
        }
    }
}
