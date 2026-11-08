package com.example.edam.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edam.model.OperationLog;
import com.example.edam.model.SysUser;
import com.example.edam.repository.OperationLogRepository;
import com.example.edam.repository.SysUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审计导出的回归测试（2026-11-08）。
 *
 * <p>盯两件事：
 * <ol>
 *   <li><b>导出的口径必须和列表一致</b> —— 同一个过滤条件，
 *       看到的和导出的要对得上。</li>
 *   <li><b>CSV 注入防护</b> —— 审计的 detail 字段是用户可控的
 *       （文件名、理由…），以 {@code = + - @} 开头的单元格
 *       会被 Excel 当成公式执行。</li>
 * </ol>
 */
class AuditServiceExportTest {

    private OperationLogRepository operationLogRepository;
    private SysUserRepository sysUserRepository;
    private AuditService service;

    @BeforeEach
    void setUp() {
        operationLogRepository = mock(OperationLogRepository.class);
        sysUserRepository = mock(SysUserRepository.class);
        service = new AuditService(operationLogRepository, sysUserRepository);
    }

    @SuppressWarnings("unchecked")
    private void stubRecords(List<OperationLog> records) {
        Page<OperationLog> page = new Page<>(1, records.size());
        page.setRecords(records);
        when(operationLogRepository.selectPage(any(Page.class), any())).thenReturn(page);
    }

    private OperationLog log(long id, String detail, String opType) {
        OperationLog ol = new OperationLog();
        ol.setId(id);
        ol.setUserId(7L);
        ol.setOperationType(opType);
        ol.setResourceType("video");
        ol.setResourceId(42L);
        ol.setResult(1);
        ol.setHttpMethod("GET");
        ol.setEndpoint("/videos/{video_id}");
        ol.setStatusCode(200);
        ol.setDurationMs(12);
        ol.setIp("10.0.0.1");
        ol.setUserAgent("curl/8");
        ol.setDetail(detail);
        ol.setTimestamp(java.time.OffsetDateTime.of(2026, 11, 8, 10, 0, 0, 0,
            java.time.ZoneOffset.ofHours(8)));
        return ol;
    }

    @Test
    @DisplayName("导出真的产出 CSV：带 BOM、有表头、有数据行")
    void exportProducesCsv() {
        stubRecords(List.of(log(1L, "正常详情", "create")));
        SysUser u = new SysUser();
        u.setId(7L);
        u.setEmployeeNo("E000007");
        when(sysUserRepository.selectBatchIds(any())).thenReturn(List.of(u));

        String csv = new String(service.exportCsv(null, null, null, null, null, null),
            StandardCharsets.UTF_8);

        // BOM —— 没有它 Excel 打开中文是乱码
        assertTrue(csv.startsWith("﻿"), "CSV 必须以 UTF-8 BOM 开头");
        assertTrue(csv.contains("时间,工号,操作类型,资源类型,资源ID,结果"), "表头不对: " + csv);
        assertTrue(csv.contains("E000007"), "工号没带出来");
        assertTrue(csv.contains("正常详情"));
        assertTrue(csv.contains("/videos/{video_id}"));
    }

    @Test
    @DisplayName("CSV 注入：detail 以 = 开头时被打断公式")
    void csvInjectionIsNeutralized() {
        stubRecords(List.of(log(1L, "=cmd|' /C calc'!A0", "create")));

        String csv = new String(service.exportCsv(null, null, null, null, null, null),
            StandardCharsets.UTF_8);

        assertTrue(csv.contains("\"'=cmd"), "公式前缀没被打断: " + csv);
        assertTrue(!csv.contains("\"=cmd"), "危险单元格没加前缀: " + csv);
    }

    @Test
    @DisplayName("CSV 注入：@ 和 + 开头同样要打断")
    void csvInjectionAtAndPlusAreNeutralized() {
        stubRecords(List.of(log(1L, "@SUM(1+1)", "create"), log(2L, "+1+1", "create")));

        String csv = new String(service.exportCsv(null, null, null, null, null, null),
            StandardCharsets.UTF_8);

        assertTrue(csv.contains("\"'@SUM"), "@ 开头没被打断: " + csv);
        assertTrue(csv.contains("\"'+1+1"), "+ 开头没被打断: " + csv);
    }

    @Test
    @DisplayName("单元格里的引号和换行被正确转义")
    void csvEscapesQuotesAndNewlines() {
        stubRecords(List.of(log(1L, "他说\"这是\"引号\n还带换行", "create")));

        String csv = new String(service.exportCsv(null, null, null, null, null, null),
            StandardCharsets.UTF_8);

        assertTrue(csv.contains("\"\"这是\"\""), "引号没转义: " + csv);
        assertTrue(!csv.contains("\n还带换行"), "换行没被压平: " + csv);
    }

    @Test
    @DisplayName("超过行数上限 ⇒ 400，要求缩小时间范围")
    void exportRespectsRowCap() {
        List<OperationLog> many = new ArrayList<>();
        for (int i = 0; i <= AuditService.MAX_EXPORT_ROWS; i++) {
            many.add(log(i, "d", "create"));
        }
        stubRecords(many);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.exportCsv(null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("缩小时间范围"), "错误信息要给出可操作指引: " + ex.getMessage());
    }
}
