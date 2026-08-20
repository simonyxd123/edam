package com.example.edam.security.classification;

/**
 * 数据分类密级（v3.3 W-7.1）
 *
 * L1 公开 / L2 内部 / L3 机密 / L4 绝密
 */
public enum ClassificationLevel {

    L1_PUBLIC(1, "公开", "L1", "可对外公开"),
    L2_INTERNAL(2, "内部", "L2", "仅限内部员工"),
    L3_CONFIDENTIAL(3, "机密", "L3", "限部门/项目组"),
    L4_TOP_SECRET(4, "绝密", "L4", "限少数核心人员");

    private final int level;
    private final String name;
    private final String code;
    private final String description;

    ClassificationLevel(int level, String name, String code, String description) {
        this.level = level;
        this.name = name;
        this.code = code;
        this.description = description;
    }

    public int getLevel() { return level; }
    public String getName() { return name; }
    public String getCode() { return code; }
    public String getDescription() { return description; }

    /**
     * 从代码字符串解析
     */
    public static ClassificationLevel fromCode(String code) {
        if (code == null) return L1_PUBLIC;
        return switch (code.toUpperCase()) {
            case "L1" -> L1_PUBLIC;
            case "L2" -> L2_INTERNAL;
            case "L3" -> L3_CONFIDENTIAL;
            case "L4" -> L4_TOP_SECRET;
            default -> L1_PUBLIC;
        };
    }

    /**
     * 比较敏感度（higher = more sensitive）
     */
    public boolean isAtLeast(ClassificationLevel other) {
        return this.level >= other.level;
    }
}