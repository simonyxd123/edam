package com.example.edam.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 密码历史表（v3.3 W-1 G-5）
 */
@Data
@TableName("sys_password_history")
public class SysPasswordHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** bcrypt hash */
    private String passwordHash;

    private LocalDateTime changedAt;

    /** 修改人（用户自己 / 管理员） */
    private Long changedBy;

    /** rotation / reset / admin / security */
    private String changeReason;
}