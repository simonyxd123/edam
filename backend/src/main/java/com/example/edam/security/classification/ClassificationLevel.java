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
     * <b>严格</b>解析 —— 非法值返回 {@code null}，**不降级**。
     *
     * <p>{@link #fromCode} 对非法值静默降级成 L1（公开，最宽松）——
     * 用在校验场景下等于"没校验"：客户端传 {@code "L9"} 或
     * {@code "公开"} 都会被悄悄当成 L1 放行。
     *
     * <p>密级是防泄密系统的核心字段，服务端校验必须用这个方法。
     */
    public static ClassificationLevel fromCodeOrNull(String code) {
        if (code == null) return null;
        return switch (code.trim().toUpperCase()) {
            case "L1" -> L1_PUBLIC;
            case "L2" -> L2_INTERNAL;
            case "L3" -> L3_CONFIDENTIAL;
            case "L4" -> L4_TOP_SECRET;
            default -> null;
        };
    }

    /**
     * <b>严格</b>解析 —— 非法值直接抛 {@link IllegalArgumentException}（→ 400）。
     *
     * <p>空白（null / 空串）视为"未声明"，返回 {@code null} ——
     * 由服务端裁决器决定密级。
     *
     * <p>用在请求入口做前置校验：非法密级应该在**文件还没开始传**的时候
     * 就被拒掉，而不是等 8GB 传完、进了 MinIO 才发现。
     */
    public static ClassificationLevel parseStrict(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        ClassificationLevel level = fromCodeOrNull(code);
        if (level == null) {
            throw new IllegalArgumentException(
                "密级取值非法: '" + code + "'，合法取值为 L1 / L2 / L3 / L4");
        }
        return level;
    }

    /**
     * 比较敏感度（higher = more sensitive）
     */
    public boolean isAtLeast(ClassificationLevel other) {
        return this.level >= other.level;
    }
}