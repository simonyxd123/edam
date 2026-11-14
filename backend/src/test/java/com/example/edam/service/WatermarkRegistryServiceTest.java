package com.example.edam.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.edam.model.WatermarkCache;
import com.example.edam.repository.WatermarkCacheRepository;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WatermarkRegistryService} 的单测。
 *
 * <p><b>为什么要有这个登记表</b>：指纹文本是**文件内容的一部分**，
 * 谁都能自己写进去。没有登记值时，提取端只能按 {@code doc_id} 反查 ⇒
 * 伪造者可以让系统把无辜的人指认为泄密者。有了登记值，
 * 伪造出来的指纹<b>对不上</b> ⇒ 被识破。
 */
class WatermarkRegistryServiceTest {

    private WatermarkCacheRepository repository;
    private WatermarkRegistryService service;

    private static final Long DOC_ID = 7L;
    private static final Long UPLOADER_ID = 10L;
    private static final String FINGERPRINT = "10|7|2026-11-14T10:00:00Z";

    @BeforeEach
    void setUp() {
        // MP 的 lambda 缓存：单测里没有 MyBatis 容器，要手工初始化
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            WatermarkCache.class);
        repository = mock(WatermarkCacheRepository.class);
        service = new WatermarkRegistryService(repository);
    }

    private WatermarkCache row(String fingerprint) {
        WatermarkCache w = new WatermarkCache();
        w.setId(1L);
        w.setResourceId(DOC_ID);
        w.setResourceType(WatermarkRegistryService.RESOURCE_TYPE_DOCUMENT);
        w.setUserIdHash("hash");
        w.setFingerprint(fingerprint);
        w.setMinioPath("documents/7/watermarked.pdf");
        w.setTtlSec(WatermarkRegistryService.REGISTRY_TTL_SEC);
        w.setHitCount(0);
        return w;
    }

    @Test
    @DisplayName("没有登记记录 ⇒ UNKNOWN（历史文档，照常按老逻辑出结论）")
    void unknownWhenNoEntry() {
        when(repository.selectList(any())).thenReturn(List.of());
        assertEquals(WatermarkRegistryService.Verdict.UNKNOWN,
            service.verify(DOC_ID, FINGERPRINT));
    }

    @Test
    @DisplayName("提取到的指纹与登记值一致 ⇒ MATCHED")
    void matchedWhenSameFingerprint() {
        when(repository.selectList(any())).thenReturn(List.of(row(FINGERPRINT)));
        assertEquals(WatermarkRegistryService.Verdict.MATCHED,
            service.verify(DOC_ID, FINGERPRINT));
    }

    @Test
    @DisplayName("提取到的指纹与登记值不一致 ⇒ MISMATCHED（伪造）")
    void mismatchedWhenFingerprintRewritten() {
        when(repository.selectList(any())).thenReturn(List.of(row(FINGERPRINT)));
        assertEquals(WatermarkRegistryService.Verdict.MISMATCHED,
            service.verify(DOC_ID, "10|7|2020-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("登记：先更新、没更新到再插入（幂等，不产生第二行）")
    void registerIsIdempotent() {
        when(repository.update(isNull(), any())).thenReturn(1);
        service.register(DOC_ID, UPLOADER_ID, FINGERPRINT, "documents/7/watermarked.pdf");
        verify(repository, never()).insert(any(WatermarkCache.class));

        when(repository.update(isNull(), any())).thenReturn(0);
        service.register(DOC_ID, UPLOADER_ID, FINGERPRINT, "documents/7/watermarked.pdf");
        ArgumentCaptor<WatermarkCache> captor = ArgumentCaptor.forClass(WatermarkCache.class);
        verify(repository).insert(captor.capture());
        assertEquals(DOC_ID, captor.getValue().getResourceId());
        assertEquals(FINGERPRINT, captor.getValue().getFingerprint());
        assertEquals(WatermarkRegistryService.RESOURCE_TYPE_DOCUMENT,
            captor.getValue().getResourceType());
        // 保留期必须远大于建表时的默认值 86400（1 天）——
        // 取证证据不能用"缓存"的 TTL，否则泄露几个月后才查就查不到了
        assertEquals(WatermarkRegistryService.REGISTRY_TTL_SEC,
            captor.getValue().getTtlSec());
    }

    @Test
    @DisplayName("登记：指纹为空 ⇒ 不写库")
    void registerIgnoresBlankFingerprint() {
        service.register(DOC_ID, UPLOADER_ID, "  ", null);
        verify(repository, never()).insert(any(WatermarkCache.class));
    }

    @Test
    @DisplayName("listByResource：按资源查登记记录")
    void listByResourceReturnsRows() {
        when(repository.selectList(any(Wrapper.class))).thenReturn(List.of(row(FINGERPRINT)));
        List<WatermarkCache> rows = service.listByResource(DOC_ID, "document");
        assertEquals(1, rows.size());
        assertEquals(FINGERPRINT, rows.get(0).getFingerprint());
    }
}
