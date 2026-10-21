package com.example.edam.security;

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

    private final SysUserRepository userRepository;

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
