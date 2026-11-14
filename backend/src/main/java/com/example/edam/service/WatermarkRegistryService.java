package com.example.edam.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.edam.model.WatermarkCache;
import com.example.edam.repository.WatermarkCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * 水印指纹登记服务 —— <b>把"这份文档当初嵌的是哪串文本"记下来</b>。
 *
 * <p><b>为什么必须有它</b>（第五轮审计 §3.3 的残留缺口，本轮补上）：
 * 指纹文本是<em>文件内容的一部分</em>，谁都能自己写进去。
 * 第五轮加的一致性校验（指纹里的 uploader_id 必须等于库里该文档的上传者）
 * 只能挡住"随手伪造一个指针"；<b>知道目标 doc_id 与 uploader_id 的人
 * 仍能伪造出一致的指纹</b> —— 因为系统里没有"当初嵌的是哪串文本"的登记。
 *
 * <p>本服务就是那份登记：
 * <ol>
 *   <li>Worker 嵌入水印时把<b>实际嵌入的文本</b>随状态回调回写后端；</li>
 *   <li>后端落进 {@code watermark_cache}；</li>
 *   <li>提取时拿提取到的文本与登记值比对 ⇒ <b>对不上就是伪造</b>。</li>
 * </ol>
 *
 * <p>这同时缓解了 PDF / Excel 的"明文 metadata 指纹"问题（第六轮 §2）：
 * 明文指纹可以被<b>改写</b>，但改写之后它就对不上登记值了 ⇒ 会被识破。
 *
 * <p><b>老数据怎么办</b>：本功能上线前处理过的文档没有登记记录。
 * 比对结果为 {@link Verdict#UNKNOWN} 时**照常按老逻辑出结论**（第五轮的一致性校验仍然生效），
 * 只在日志里留一条"无登记" —— 不能因为上线了新功能就让历史文档查不出人。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatermarkRegistryService {

    /** 资源类型：文档 */
    public static final int RESOURCE_TYPE_DOCUMENT = 2;

    /**
     * 登记记录的保留期（秒）—— 10 年。
     *
     * <p>建表时 {@code ttl_sec} 的默认语义是"缓存 1 天"，但这里存的是
     * <b>取证证据</b>：泄露文件可能在几个月甚至几年后才被发现、才来溯源。
     * 保留期太短 = 证据过期，溯源能力随时间静默衰减。
     */
    public static final int REGISTRY_TTL_SEC = 10 * 365 * 24 * 3600;

    private final WatermarkCacheRepository repository;

    /**
     * 登记一份文档实际嵌入的指纹。
     *
     * <p>幂等：同一份文档被重复处理（Worker 重试）时覆盖旧值，
     * 不产生第二行（表上有 {@code UNIQUE(resource_id, resource_type, user_id_hash)}）。
     */
    @Transactional
    public void register(Long docId, Long uploaderId, String fingerprint, String minioPath) {
        if (docId == null || fingerprint == null || fingerprint.isBlank()) {
            return;
        }
        String hash = hashUserId(uploaderId);
        int updated = repository.update(null, new LambdaUpdateWrapper<WatermarkCache>()
            .eq(WatermarkCache::getResourceId, docId)
            .eq(WatermarkCache::getResourceType, RESOURCE_TYPE_DOCUMENT)
            .eq(WatermarkCache::getUserIdHash, hash)
            .set(WatermarkCache::getFingerprint, fingerprint)
            .set(WatermarkCache::getMinioPath, minioPath == null ? "" : minioPath)
            .set(WatermarkCache::getTtlSec, REGISTRY_TTL_SEC)
            .set(WatermarkCache::getHitCount, 0));
        if (updated > 0) {
            log.info("watermark_registry_updated, doc_id={}, uploader_id={}", docId, uploaderId);
            return;
        }
        WatermarkCache entry = new WatermarkCache();
        entry.setResourceId(docId);
        entry.setResourceType(RESOURCE_TYPE_DOCUMENT);
        entry.setUserIdHash(hash);
        entry.setFingerprint(fingerprint);
        entry.setMinioPath(minioPath == null ? "" : minioPath);
        entry.setCreatedAt(LocalDateTime.now());
        entry.setTtlSec(REGISTRY_TTL_SEC);
        entry.setHitCount(0);
        try {
            repository.insert(entry);
            log.info("watermark_registry_inserted, doc_id={}, uploader_id={}", docId, uploaderId);
        } catch (Exception e) {
            // 并发下另一个线程可能刚插了同一行 —— 唯一键冲突不算失败
            log.warn("watermark_registry_insert_conflict, doc_id={}, error={}",
                docId, e.getClass().getSimpleName());
        }
    }

    /**
     * 比对提取到的指纹与登记值。
     *
     * @return {@link Verdict#MATCHED} 一致；{@link Verdict#MISMATCHED} <b>伪造</b>；
     *         {@link Verdict#UNKNOWN} 没有登记记录（上线前的历史文档）
     */
    @Transactional
    public Verdict verify(Long docId, String fingerprint) {
        if (docId == null || fingerprint == null || fingerprint.isBlank()) {
            return Verdict.UNKNOWN;
        }
        List<WatermarkCache> rows = repository.selectList(
            new LambdaQueryWrapper<WatermarkCache>()
                .eq(WatermarkCache::getResourceId, docId)
                .eq(WatermarkCache::getResourceType, RESOURCE_TYPE_DOCUMENT));
        if (rows == null || rows.isEmpty()) {
            return Verdict.UNKNOWN;
        }
        for (WatermarkCache row : rows) {
            if (fingerprint.equals(row.getFingerprint())) {
                repository.update(null, new LambdaUpdateWrapper<WatermarkCache>()
                    .eq(WatermarkCache::getId, row.getId())
                    .setSql("hit_count = hit_count + 1"));
                return Verdict.MATCHED;
            }
        }
        return Verdict.MISMATCHED;
    }

    /**
     * 按资源查登记记录（{@code /watermarks/cache} 用）。
     *
     * @param resourceType {@code null}/空 = 不限；"document" = 2；"video" = 1
     */
    public List<WatermarkCache> listByResource(Long resourceId, String resourceType) {
        LambdaQueryWrapper<WatermarkCache> wrapper = new LambdaQueryWrapper<WatermarkCache>()
            .eq(WatermarkCache::getResourceId, resourceId);
        Integer type = parseResourceType(resourceType);
        if (type != null) {
            wrapper.eq(WatermarkCache::getResourceType, type);
        }
        wrapper.orderByDesc(WatermarkCache::getCreatedAt);
        List<WatermarkCache> rows = repository.selectList(wrapper);
        return rows == null ? List.of() : rows;
    }

    /** 资源类型名 → 库里存的数字；认不出来返回 null（不限）。 */
    private Integer parseResourceType(String resourceType) {
        if (resourceType == null || resourceType.isBlank()) {
            return null;
        }
        String t = resourceType.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.equals("document") || t.equals("doc") || t.equals("2")) {
            return RESOURCE_TYPE_DOCUMENT;
        }
        if (t.equals("video") || t.equals("1")) {
            return 1;
        }
        return null;
    }

    /**
     * 登记记录的查询结果。
     */
    public enum Verdict {
        /** 与登记值一致 */
        MATCHED,
        /** 与登记值不一致 —— 指纹被改写过，视为伪造 */
        MISMATCHED,
        /** 没有登记记录（本功能上线前的历史文档） */
        UNKNOWN
    }

    /** 用户 ID 的 SHA-256（与 {@code JwtTokenProvider.hashUserId} 同一算法） */
    private String hashUserId(Long userId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.valueOf(userId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
