package com.example.edam.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 水印指纹登记表（表 {@code watermark_cache}）。
 *
 * <p><b>这张表此前没有任何写入方</b> —— 建表之后一直空着，
 * {@code WatermarkController.listCache} 的注释里写着这件事。
 * 第五轮审计把它列为"溯源可冤枉人"残留缺口的堵法（§3.3），
 * 本轮接上了写入方：Worker 嵌入水印时把**实际嵌入的那串文本**
 * 回写到这里，提取时拿提取结果与登记值比对 ⇒ 对不上就是伪造。
 *
 * <p>字段与建表语句（{@code V20260812_1000__init_schema.sql}）严格一致：
 * <pre>
 *   resource_id     BIGINT UNSIGNED NOT NULL  -- 文档 ID
 *   resource_type   TINYINT NOT NULL          -- 1=video 2=document
 *   user_id_hash    CHAR(64) NOT NULL         -- 上传者 ID 的 SHA-256
 *   fingerprint     TEXT NOT NULL             -- 实际嵌入的指纹文本
 *   minio_path      VARCHAR(512) NOT NULL     -- 水印产物在 MinIO 里的对象名
 *   ttl_sec         INT UNSIGNED NOT NULL     -- 保留期（秒）
 *   hit_count       INT UNSIGNED NOT NULL     -- 命中次数
 *   UNIQUE (resource_id, resource_type, user_id_hash)
 * </pre>
 *
 * <p>⚠️ {@code ttl_sec} 的原设计语义是"缓存 TTL"（默认 1 天），
 * 但登记 forensic 记录用它会**让证据过期** —— 泄露文件可能在几个月后才被发现。
 * ⇒ 本服务写入时显式给一个远大于默认值的 TTL（{@link WatermarkRegistryService#REGISTRY_TTL_SEC}）。
 */
@Data
@TableName("watermark_cache")
public class WatermarkCache {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 资源 ID（登记场景下 = 文档 ID） */
    private Long resourceId;

    /** 资源类型：1=video 2=document */
    private Integer resourceType;

    /** 上传者 ID 的 SHA-256（不落明文用户 ID） */
    private String userIdHash;

    /** 实际嵌入的指纹文本 */
    private String fingerprint;

    /** 水印产物在 MinIO 里的对象名 */
    private String minioPath;

    private LocalDateTime createdAt;

    /** 保留期（秒） */
    private Integer ttlSec;

    /** 命中次数（比对成功时递增） */
    private Integer hitCount;
}
