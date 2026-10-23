package com.example.edam.dto;

/**
 * 资源状态枚举的 Integer ↔ String 映射（单一事实来源）。
 *
 * <p><b>为什么要这层</b>：DB 里这些字段是 {@code Integer}
 * （classification_lv 1~4、hls_status 0~3、watermark_status 0~4），
 * 而前端把它们当字符串用（{@code 'ready'} / {@code 'L2'}）。
 * 以前后端 list / detail 接口直接返回实体，于是：
 * <ul>
 *   <li>前端 {@code row.hls_status === 'ready'} 恒为 false → 状态标签恒错</li>
 *   <li>前端 {@code getClassificationTag(2)} 取不到 → 密级列显示裸数字 2</li>
 *   <li>Videos 页的 HLS 轮询两个分支都不进 → <b>每次上传都必然等满
 *       10 分钟才报"处理超时"</b></li>
 * </ul>
 *
 * <p>统一在 DTO 层做映射，DB 层保持 Integer 不动。
 */
public final class ResourceEnums {

    private ResourceEnums() {
    }

    /** 密级：1=L1 公开 2=L2 内部 3=L3 机密 4=L4 绝密 */
    public static String classificationLv(Integer lv) {
        if (lv == null) return "L1";
        return switch (lv) {
            case 1 -> "L1";
            case 2 -> "L2";
            case 3 -> "L3";
            case 4 -> "L4";
            default -> "L1";
        };
    }

    /** HLS 状态：0=pending 1=processing 2=ready 3=failed */
    public static String hlsStatus(Integer status) {
        if (status == null) return "pending";
        return switch (status) {
            case 0 -> "pending";
            case 1 -> "processing";
            case 2 -> "ready";
            case 3 -> "failed";
            default -> "unknown";
        };
    }

    /**
     * 外发审批状态：0=pending 1=approved 2=rejected 3=expired 4=revoked
     *
     * <p>与视频/文档的状态语义不同（那边是 processing/ready/failed），
     * 所以单独一个方法，不强行合并。
     */
    public static String approvalStatus(Integer status) {
        if (status == null) return "pending";
        return switch (status) {
            case 0 -> "pending";
            case 1 -> "approved";
            case 2 -> "rejected";
            case 3 -> "expired";
            case 4 -> "revoked";
            default -> "unknown";
        };
    }

    /** 水印 / 预览状态：0=pending 1=processing 2=ready 3=failed 4=skipped */
    public static String processStatus(Integer status) {
        if (status == null) return "pending";
        return switch (status) {
            case 0 -> "pending";
            case 1 -> "processing";
            case 2 -> "ready";
            case 3 -> "failed";
            case 4 -> "skipped";
            default -> "unknown";
        };
    }
}
