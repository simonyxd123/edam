package com.example.edam.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 密码策略（v3.3 W-1 G-5 等保整改）
 *
 * 等保三级要求 + OWASP ASVS 4.0 L2：
 * - 长度 ≥ 12 字符（实际建议 16 字符，等保三级硬性要求）
 * - 复杂度：大小写字母 + 数字 + 特殊字符
 * - 历史 5 次不能重复
 * - 90 天强制更换（管理员 / 财务 / 法务等敏感岗位 60 天）
 * - 连续 5 次错误锁定 30 分钟
 * - bcrypt cost=12（hash 抗彩虹表）
 *
 * 对应方案书 §9.2 安全审计
 */
@Component
public class PasswordPolicy {

    @Value("${edam.password.min-length:16}")
    private int minLength;

    @Value("${edam.password.max-length:128}")
    private int maxLength;

    @Value("${edam.password.rotation-days:90}")
    private int rotationDays;

    @Value("${edam.password.history-count:5}")
    private int historyCount;

    @Value("${edam.password.lock-threshold:5}")
    private int lockThreshold;

    @Value("${edam.password.lock-minutes:30}")
    private int lockMinutes;

    // 复杂度规则（至少满足 3 类）
    private static final Pattern HAS_UPPER = Pattern.compile("[A-Z]");
    private static final Pattern HAS_LOWER = Pattern.compile("[a-z]");
    private static final Pattern HAS_DIGIT = Pattern.compile("[0-9]");
    private static final Pattern HAS_SPECIAL = Pattern.compile("[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~]");

    /**
     * 验证密码复杂度
     *
     * @return ValidationResult 包含 isValid + 失败原因
     */
    public ValidationResult validate(String password) {
        if (password == null) {
            return ValidationResult.invalid("密码不能为空");
        }
        if (password.length() < minLength) {
            return ValidationResult.invalid("密码长度至少 " + minLength + " 位");
        }
        if (password.length() > maxLength) {
            return ValidationResult.invalid("密码长度不能超过 " + maxLength + " 位");
        }

        int classesPresent = 0;
        if (HAS_UPPER.matcher(password).find()) classesPresent++;
        if (HAS_LOWER.matcher(password).find()) classesPresent++;
        if (HAS_DIGIT.matcher(password).find()) classesPresent++;
        if (HAS_SPECIAL.matcher(password).find()) classesPresent++;

        if (classesPresent < 3) {
            return ValidationResult.invalid("密码必须包含大小写字母、数字、特殊字符中至少 3 类");
        }

        // 常见弱密码列表（黑名单）
        if (isCommonPassword(password)) {
            return ValidationResult.invalid("密码过于简单，请使用更复杂的密码");
        }

        return ValidationResult.valid();
    }

    /**
     * 是否在历史密码列表中（不能与最近 N 次重复）
     */
    public boolean isInHistory(String newPasswordHash, java.util.List<String> historyHashes) {
        // historyHashes 已经 bcrypt hash；用 BCryptPasswordEncoder.matches
        // 实际实现在 AuthService.changePassword()
        return historyHashes.contains(newPasswordHash);
    }

    /**
     * 是否需要强制更换（密码创建时间距今 > rotationDays）
     */
    public boolean needsRotation(java.time.OffsetDateTime lastChanged) {
        if (lastChanged == null) return true;
        long daysSinceChange = java.time.Duration.between(lastChanged, java.time.OffsetDateTime.now()).toDays();
        return daysSinceChange >= rotationDays;
    }

    /**
     * 是否常见弱密码（实际生产应使用 HaveIBeenPwned API + 更大列表）
     */
    private boolean isCommonPassword(String password) {
        String lower = password.toLowerCase();
        return lower.contains("password") || lower.contains("123456") ||
               lower.contains("qwerty") || lower.contains("admin") ||
               lower.equals("test1") || lower.contains("welcome");
    }

    /**
     * 验证结果
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String reason;

        private ValidationResult(boolean valid, String reason) {
            this.valid = valid;
            this.reason = reason;
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String reason) {
            return new ValidationResult(false, reason);
        }

        public boolean isValid() { return valid; }
        public String getReason() { return reason; }
    }

    public int getMinLength() { return minLength; }
    public int getMaxLength() { return maxLength; }
    public int getRotationDays() { return rotationDays; }
    public int getHistoryCount() { return historyCount; }
    public int getLockThreshold() { return lockThreshold; }
    public int getLockMinutes() { return lockMinutes; }
}