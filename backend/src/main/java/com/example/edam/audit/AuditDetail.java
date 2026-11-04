package com.example.edam.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 审计 detail 的拼装工具：{@code k=v, k2=v2} 格式，并对敏感字段脱敏。
 *
 * <p><b>为什么必须脱敏</b>：审计日志本身也是一份数据，权限往往比业务数据还松
 * （审计员能看全局），而且会被导出、被归档、被拷到 OSS 冷存。
 * 一旦里面有一条明文密码 / refresh_token / 密钥，这份日志就成了新的泄露面。
 *
 * <p>脱敏按**键名**判定，与值长什么样无关 —— 值是"abc"还是"hunter2"，
 * 只要键名命中就一律替换成 {@code ***}。
 */
public final class AuditDetail {

    private AuditDetail() {
    }

    /**
     * 敏感键名（大小写不敏感，按"包含"匹配）。
     *
     * <p>刻意<b>不</b>包含泛化的 {@code code} —— 项目里 {@code code} 大量表示
     * 角色码、错误码、HTTP 状态码，是业务信息，不是凭据。宁可漏一个，
     * 也不能把整张审计表的业务信息都抹掉。
     */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
        "password", "passwd", "pwd",
        "old_password", "new_password", "oldpassword", "newpassword",
        "token", "access_token", "accesstoken", "refresh_token", "refreshtoken",
        "id_token", "idtoken", "authorization", "auth",
        "secret", "client_secret", "private_key", "privatekey",
        "apikey", "api_key", "credential", "credentials",
        "mfa_code", "mfacode", "otp", "totp",
        "signature", "sign", "session_key"
    );

    /** 脱敏后的占位符 */
    public static final String MASK = "***";

    /**
     * 判断一个键名是否敏感。
     */
    public static boolean isSensitive(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase(Locale.ROOT);
        for (String s : SENSITIVE_KEYS) {
            if (k.contains(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 拼装 detail：{@code kv("title", "某视频", "size", 1024)} ⇒ {@code title=某视频, size=1024}。
     *
     * <p>参数是**成对**的：偶数个，第 2i 个是键，第 2i+1 个是值。
     * 键为 null 的对会被跳过；值为 null 时写成 {@code k=null}（"这个字段没传"本身是信息）。
     * 敏感键的值一律写成 {@link #MASK}。
     */
    public static String kv(Object... pairs) {
        if (pairs == null || pairs.length == 0) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object key = pairs[i];
            if (key == null) {
                continue;
            }
            String k = String.valueOf(key);
            Object value = pairs[i + 1];
            parts.add(k + "=" + (isSensitive(k) ? MASK : String.valueOf(value)));
        }
        return String.join(", ", parts);
    }

    /**
     * 自由文本里的凭据脱敏：把形如 {@code xxx=yyyy} 的敏感键值替换掉。
     *
     * <p>用于拼接异常原因这类不受控文本 ——
     * 异常消息里完全可能出现 {@code password=xxx}。
     */
    public static String scrub(String freeText) {
        if (freeText == null || freeText.isBlank()) {
            return freeText;
        }
        String out = freeText;
        for (String s : SENSITIVE_KEYS) {
            // 匹配 key=value / key: value / "key":"value"
            out = out.replaceAll("(?i)(" + java.util.regex.Pattern.quote(s) + "\\s*[=:]\\s*)(\"?)([^\\s,\"'&}]+)\\2",
                "$1" + MASK);
        }
        return out;
    }
}
