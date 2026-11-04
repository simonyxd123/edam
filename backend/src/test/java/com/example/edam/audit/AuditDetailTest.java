package com.example.edam.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AuditDetail} 的脱敏单测。
 *
 * <p><b>为什么这个用例必须存在</b>：审计日志本身也是一份数据 ——
 * 审计员能看全局、日志会被导出、会被归档到冷存。
 * 一旦里面有一条明文密码 / refresh_token / 密钥，
 * 这份日志就成了新的泄露面，而它往往比原始业务数据的权限更松。
 */
class AuditDetailTest {

    @Test
    @DisplayName("敏感键名的值被替换成 ***")
    void sensitiveValuesAreMasked() {
        String detail = AuditDetail.kv(
            "employee_no", "E000101",
            "password", "hunter2",
            "token", "eyJhbGciOi.JIUzI1NiJ9.sig");

        assertTrue(detail.contains("employee_no=E000101"), "非敏感字段要原样保留：" + detail);
        assertTrue(detail.contains("password=***"), "密码必须打码：" + detail);
        assertTrue(detail.contains("token=***"), "token 必须打码：" + detail);
        assertFalse(detail.contains("hunter2"), "明文密码不能出现在审计里：" + detail);
        assertFalse(detail.contains("eyJhbGciOi"), "明文 token 不能出现在审计里：" + detail);
    }

    @Test
    @DisplayName("大小写不敏感 + 包含匹配（refresh_token / NEW_PASSWORD 都要打码）")
    void keyMatchIsCaseInsensitiveAndSubstring() {
        assertTrue(AuditDetail.isSensitive("Refresh_Token"));
        assertTrue(AuditDetail.isSensitive("NEW_PASSWORD"));
        assertTrue(AuditDetail.isSensitive("oldPassword"));
        assertTrue(AuditDetail.isSensitive("X-Access-Token"));
    }

    @Test
    @DisplayName("code 不算敏感 —— 角色码/错误码是业务信息，不能整片抹掉")
    void codeIsNotSensitive() {
        assertFalse(AuditDetail.isSensitive("code"));
        assertFalse(AuditDetail.isSensitive("status_code"));
        assertFalse(AuditDetail.isSensitive("http_code"));
    }

    @Test
    @DisplayName("自由文本里的凭据也被脱敏（异常消息里常出现 password=xxx）")
    void scrubRemovesCredentialsFromFreeText() {
        String scrubbed = AuditDetail.scrub("登录失败: password=hunter2, user=E001");
        assertFalse(scrubbed.contains("hunter2"), "异常消息里的密码也要打码：" + scrubbed);
        assertTrue(scrubbed.contains("user=E001"), "非凭据部分要保留：" + scrubbed);
    }

    @Test
    @DisplayName("null / 空 detail 原样返回")
    void nullAndBlankPassThrough() {
        assertEquals(null, AuditDetail.scrub(null));
        assertEquals("  ", AuditDetail.scrub("  "));
        assertEquals("", AuditDetail.kv());
    }

    @Test
    @DisplayName("值为 null 时写成 k=null（'这个字段没传'本身是信息）")
    void nullValueIsKept() {
        assertEquals("a=null", AuditDetail.kv("a", null));
    }
}
