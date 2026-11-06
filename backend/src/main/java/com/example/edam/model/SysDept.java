package com.example.edam.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 部门（sys_dept）
 *
 * <p>此前 Java 侧**没有**这个实体 —— 于是"按上传者部门定密级"这条规则
 * 根本没法落地（{@code DataClassificationService} 里匹配的是
 * {@code hr} / {@code 法务} / {@code 财务} 这类名字，而
 * {@code sys_dept} 的种子数据是 {@code 技术部} / {@code 产品部} …）。
 *
 * <p>部门是**服务端可信数据**（不是客户端传的），所以它是密级裁决里
 * 唯一能当"基线"用的信号 —— 文件名和文件大小都是客户端可控的。
 */
@Data
@TableName("sys_dept")
public class SysDept {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 部门名称，如「技术部」「法务部」 */
    private String name;

    private Long parentId;

    /** 物化路径，如 /1/2/ */
    private String path;

    /** 层级，1 = 总公司 */
    private Integer level;

    private Integer sortOrder;

    private LocalDateTime createdAt;
}
