package com.example.edam.security;

import com.example.edam.exception.AccountLockedException;
import com.example.edam.model.SysUser;
import com.example.edam.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;

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
 *
 * <p><b>2026-11-14 代码审计第五轮：锁定会到期</b>
 *
 * <p>此前"锁定"只有 {@code status=3} 一个状态位，
 * <b>没有到期时间、也没有任何地方把它改回去</b> ⇒
 * 连错 5 次密码 = 账号<b>永久</b>锁死，只能管理员手工改库；
 * 而对外一直宣称"锁定 30 分钟"（异常里还把 1800 秒当剩余时间告诉客户端）。
 *
 * <p>现在锁定是一个<b>有时间窗的状态</b>：
 * <ul>
 *   <li>连续失败触发 ⇒ 写 {@code locked_until = now + 30 分钟}，到期自动解锁；</li>
 *   <li>管理员手动锁定（{@code locked_until} 为 null）⇒ 不自动解锁，必须人工恢复。</li>
 * </ul>
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
     * 用于「自动解锁」这一步的**独立事务**。
     *
     * <p>为什么不能直接用 {@code @Transactional(REQUIRES_NEW)} 注解：
     * {@link #assertLoginAllowed} 与解锁写库在**同一个 bean** 里，
     * 自调用不走代理 ⇒ 注解失效 ⇒ 解锁会被外层登录事务的回滚一起带走
     * （登录随后失败 ⇒ 回滚 ⇒ 账号仍旧锁着 ⇒ 锁永远到期不了）。
     * {@link TransactionTemplate} 显式指定传播行为，没有这个坑。
     */
    private final PlatformTransactionManager transactionManager;

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
            // 锁定中 —— 但先看**是不是已经到期**。
            if (isLockExpired(user)) {
                autoUnlock(user);
            } else {
                throw new AccountLockedException(remainingLockSeconds(user));
            }
        }
        if (status != null && status == 2) {
            throw new IllegalArgumentException("账号已禁用，请联系管理员");
        }
    }

    /**
     * 锁是否已到期。
     *
     * <p>{@code locked_until} 为 null ⇒ 管理员手动锁定 ⇒ **不算到期**
     * （必须人工恢复；否则"管理员锁的号"会被自动解开，管理动作就形同虚设）。
     */
    boolean isLockExpired(SysUser user) {
        OffsetDateTime until = user.getLockedUntil();
        return until != null && !OffsetDateTime.now().isBefore(until);
    }

    /**
     * 剩余锁定秒数。
     *
     * <p>管理员手动锁定（无到期时间）⇒ 返回 {@link Long#MAX_VALUE}，
     * 异常消息会提示"需要联系管理员解锁"，而不是谎报一个 30 分钟。
     */
    long remainingLockSeconds(SysUser user) {
        OffsetDateTime until = user.getLockedUntil();
        if (until == null) {
            return Long.MAX_VALUE;
        }
        long seconds = Duration.between(OffsetDateTime.now(), until).getSeconds();
        return Math.max(0, seconds);
    }

    /**
     * 自动解锁 —— 锁到期后恢复账号。
     *
     * <p>走**独立事务**：它必须在外层登录事务的回滚之外生效。
     * 否则用户到期后第一次登录如果密码又输错了，整个登录事务回滚 ⇒
     * 解锁也被回滚 ⇒ 账号继续锁着 ⇒ 锁永远解不开（那还不如不改）。
     */
    private void autoUnlock(SysUser user) {
        Long userId = user.getId();
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(status -> {
            user.setStatus(1);
            user.setFailedLoginCount(0);
            user.setLockedUntil(null);
            userRepository.updateById(user);
        });
        log.info("account_auto_unlocked, employee_no={}, user_id={}", user.getEmployeeNo(), userId);
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
                // 锁到什么时候 —— 这一行就是"锁定 30 分钟"这句话的兑现处。
                // （此前压根没写，导致锁定永不解开）
                user.setLockedUntil(OffsetDateTime.now().plusMinutes(LOCK_DURATION_MINUTES));
                log.warn("账号锁定: employee_no={} failed_count={} locked_until={}",
                    user.getEmployeeNo(), failedCount, user.getLockedUntil());
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
            user.setLockedUntil(null);
            userRepository.updateById(user);
        } catch (Exception e) {
            log.error("login_success_record_failed, employee_no={}", user.getEmployeeNo(), e);
        }
    }
}
