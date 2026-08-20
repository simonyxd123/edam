package com.example.edam.security.sso;

import lombok.Data;

import java.util.List;

/**
 * SSO 用户信息（v3.3 W-4.1）
 *
 * 从 IdP 解析后的标准化用户信息
 */
@Data
public class SsoUserInfo {
    /** IdP 唯一用户 ID（sub claim）*/
    private String userId;

    /** 工号（EDAM employee_no）*/
    private String employeeNo;

    /** 真实姓名 */
    private String realName;

    /** 邮箱 */
    private String email;

    /** 手机号（可选）*/
    private String phone;

    /** 部门 */
    private String department;

    /** 角色列表 */
    private List<String> roles;

    /** 来源 Provider */
    private String providerId;

    /** 是否新用户（JIT 开通）*/
    private boolean newlyProvisioned;

    /** 是否已禁用（IdP 禁用账号）*/
    private boolean disabled;
}