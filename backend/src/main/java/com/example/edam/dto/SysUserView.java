package com.example.edam.dto;

import com.example.edam.model.SysUser;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * SysUser 视图 DTO（snake_case 输出）
 *
 * UserController 返回值 — SysUser 实体的驼峰字段 → API 输出的 snake_case
 * password_hash 永远脱敏（null）
 */
@Data
public class SysUserView {

    private Long id;

    private String username;

    @JsonProperty("employee_no")
    private String employeeNo;

    @JsonProperty("real_name")
    private String realName;

    private String email;

    @JsonProperty("dept_id")
    private Long deptId;

    private Integer status;

    @JsonProperty("mfa_enabled")
    private Integer mfaEnabled;

    @JsonProperty("failed_login_count")
    private Integer failedLoginCount;

    @JsonProperty("must_change_password")
    private Boolean mustChangePassword;

    @JsonProperty("last_login_at")
    private String lastLoginAt;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("updated_at")
    private String updatedAt;

    public static SysUserView from(SysUser u) {
        SysUserView v = new SysUserView();
        v.id = u.getId();
        v.username = u.getUsername();
        v.employeeNo = u.getEmployeeNo();
        v.realName = u.getRealName();
        v.email = u.getEmail();
        v.deptId = u.getDeptId();
        v.status = u.getStatus();
        v.mfaEnabled = u.getMfaEnabled();
        v.failedLoginCount = u.getFailedLoginCount();
        v.mustChangePassword = u.getMustChangePassword();
        // last_login_at / created_at / updated_at 都是 OffsetDateTime
        // 这里只展示 created_at（按需扩展）
        v.createdAt = u.getCreatedAt() != null ? u.getCreatedAt().toString() : null;
        v.updatedAt = u.getUpdatedAt() != null ? u.getUpdatedAt().toString() : null;
        v.lastLoginAt = u.getLastLoginAt() != null ? u.getLastLoginAt().toString() : null;
        return v;
    }
}
