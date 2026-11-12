package com.example.edam.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edam.audit.AuditOpType;
import com.example.edam.dto.AuditLogDTO;
import com.example.edam.model.OperationLog;
import com.example.edam.model.SysUser;
import com.example.edam.repository.OperationLogRepository;
import com.example.edam.repository.SysUserRepository;
import com.example.edam.util.PageParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 审计日志 Service
 * - 异步写入 operation_log 表
 * - 列表查询：分页 + JOIN sys_user 拼 employee_no
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    /**
     * 审计写入失败计数。
     *
     * <p>原实现只打一行 error 日志就继续 —— 合规系统里"静默丢审计记录"
     * 是严重问题（日志本身也会被轮转掉）。现在有 Prometheus 指标，
     * 可以直接配告警。
     */
    private static final io.micrometer.core.instrument.Counter AUDIT_WRITE_FAILURES =
        io.micrometer.core.instrument.Counter.builder("edam_audit_write_failures_total")
            .description("审计日志写入失败次数（合规告警指标）")
            .register(io.micrometer.core.instrument.Metrics.globalRegistry);


    private final OperationLogRepository operationLogRepository;
    private final SysUserRepository sysUserRepository;

    /**
     * detail 的最大字符数。
     *
     * <p>{@code operation_log.detail} 现在是 TEXT（V20261029_1000 之前是 JSON，
     * 而代码写的是人读字符串 ⇒ 每次写入都被 MySQL 拒绝 ⇒ 审计记录静默全丢）。
     * 类型对了，但"内容太长被截断"仍会让整条审计记录写不进去 ——
     * 审计丢失比截断严重得多，所以这里**主动截断**而不是让 DB 报错。
     *
     * <p>TEXT 上限 65535 字节，utf8mb4 最坏 4 字节/字符 ⇒ 约 16383 字符。
     * 取 2000 字符：审计详情本来就只是"补充说明"，2K 足够，
     * 也远低于上限，不会因为多字节字符踩线。
     */
    private static final int MAX_DETAIL_LENGTH = 2000;

    // ============== 写入 ==============

    @Async("auditExecutor")
    public void log(Long userId, String operationType, String resourceType,
                    Long resourceId, String result) {
        write(userId, operationType, resourceType, resourceId, result,
            null, null, null, null, null, null, null, null);
    }

    @Async("auditExecutor")
    public void log(Long userId, String operationType, String resourceType,
                    Long resourceId, String result, String detail, String ip, String userAgent) {
        write(userId, operationType, resourceType, resourceId, result,
            detail, ip, userAgent, null, null, null, null, null);
    }

    /**
     * 完整写入（审计切面 {@code AuditAspect} 走这条）。
     *
     * <p><b>异步</b>：审计写库不能拖慢业务请求。
     * 但也**不能丢** —— 线程池队列满时用 CallerRunsPolicy 由请求线程自己写。
     */
    @Async("auditExecutor")
    public void log(Long userId, String operationType, String resourceType,
                    Long resourceId, String result, String detail, String ip, String userAgent,
                    String httpMethod, String endpoint, Integer statusCode,
                    Long durationMs, String sessionId) {
        write(userId, operationType, resourceType, resourceId, result,
            detail, ip, userAgent, httpMethod, endpoint, statusCode, durationMs, sessionId);
    }

    /**
     * 真正落库的地方。
     *
     * <p>所有公开的 log(...) 最终都汇到这里 —— 保证"字段怎么填"只有一份实现，
     * 不会因为某条路径忘了填某个字段而出现半截记录。
     */
    private void write(Long userId, String operationType, String resourceType,
                       Long resourceId, String result, String detail, String ip, String userAgent,
                       String httpMethod, String endpoint, Integer statusCode,
                       Long durationMs, String sessionId) {
        try {
            OperationLog entry = new OperationLog();
            entry.setUserId(userId);
            entry.setOperationType(operationType);
            entry.setResourceType(normalizeResourceType(resourceType));
            entry.setResourceId(resourceId);
            entry.setResult(parseResult(result));
            entry.setDetail(truncateDetail(detail));
            entry.setIp(ip);
            entry.setUserAgent(userAgent);
            entry.setHttpMethod(httpMethod);
            entry.setEndpoint(endpoint);
            entry.setStatusCode(statusCode);
            entry.setDurationMs(durationMs == null ? null : durationMs.intValue());
            entry.setSessionId(sessionId);
            entry.setTimestamp(java.time.OffsetDateTime.now());
            operationLogRepository.insert(entry);
        } catch (Exception e) {
            AUDIT_WRITE_FAILURES.increment();
            log.error("audit_log_write_failed, user_id={}, op={}, endpoint={}",
                userId, operationType, endpoint, e);
        }
    }

    /**
     * 资源类型归一化成小写。
     *
     * <p>为什么要归一化：库里历史上有 {@code Document}（首字母大写，唯一一处）
     * 和其它全小写的（{@code video}/{@code user}/{@code role}/{@code auth}）。
     * 不归一化的话，按 {@code resource_type=document} 查会漏掉全部文档预览记录 ——
     * 而"谁预览过哪些文档"恰恰是防泄密审计的核心问题。
     */
    private String normalizeResourceType(String resourceType) {
        if (resourceType == null || resourceType.isBlank()) {
            return "system";
        }
        return resourceType.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** 截断超长 detail —— 宁可少写几个字，也不能让整条审计记录写不进去。 */
    private String truncateDetail(String detail) {
        if (detail == null) {
            return null;
        }
        if (detail.length() <= MAX_DETAIL_LENGTH) {
            return detail;
        }
        return detail.substring(0, MAX_DETAIL_LENGTH) + "…[truncated]";
    }

    private Integer parseResult(String result) {
        if ("success".equals(result)) return 1;
        if ("failure".equals(result)) return 2;
        if ("denied".equals(result)) return 3;
        return 2;
    }

    // ============== 查询 ==============

    /**
     * 列表（分页 + 过滤），JOIN sys_user 取 employee_no。
     *
     * @param operationType 规范操作名（如 {@code create}）。
     *                       会自动**展开成历史别名的等价集合** ——
     *                       库里存成 {@code upload} / {@code create_role} 的老记录
     *                       也能按 {@code create} 查到，否则老数据在审计页上"凭空消失"。
     * @param result        {@code success} / {@code failure} / {@code denied}，null 表示不限。
     * @param resourceType  资源类型过滤，null/空表示不限。
     */
    public Page<AuditLogDTO> list(int page, int pageSize,
                                  Long userId, String operationType,
                                  LocalDateTime startTime, LocalDateTime endTime,
                                  String result, String resourceType) {

        LambdaQueryWrapper<OperationLog> wrapper = buildFilter(
            userId, operationType, startTime, endTime, result, resourceType);
        wrapper.orderByDesc(OperationLog::getTimestamp);

        Page<OperationLog> pageResult = operationLogRepository.selectPage(
            new Page<>(PageParams.clampPage(page), PageParams.clampSize(pageSize)), wrapper);

        // 收集 userId → batch 查 sys_user
        Set<Long> userIds = pageResult.getRecords().stream()
            .map(OperationLog::getUserId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
        final Map<Long, String> empNoMap;
        if (userIds.isEmpty()) {
            empNoMap = Collections.emptyMap();
        } else {
            List<SysUser> users = sysUserRepository.selectBatchIds(userIds);
            empNoMap = users.stream()
                .collect(Collectors.toMap(SysUser::getId, SysUser::getEmployeeNo, (a, b) -> a, HashMap::new));
        }

        // 转 DTO
        List<AuditLogDTO> dtos = pageResult.getRecords().stream().map(ol -> {
            AuditLogDTO dto = new AuditLogDTO();
            dto.setId(ol.getId());
            dto.setUserId(ol.getUserId());
            dto.setEmployeeNo(empNoMap.get(ol.getUserId()));
            // 历史别名归并成规范名（库里存的是原值，不改写）
            dto.setOperationType(AuditOpType.normalize(ol.getOperationType()));
            dto.setResourceType(normalizeResourceType(ol.getResourceType()));
            dto.setResourceId(ol.getResourceId());
            dto.setIp(ol.getIp());
            dto.setUserAgent(ol.getUserAgent());
            dto.setHttpMethod(ol.getHttpMethod());
            dto.setEndpoint(ol.getEndpoint());
            dto.setStatusCode(ol.getStatusCode());
            dto.setDurationMs(ol.getDurationMs());
            dto.setSessionId(ol.getSessionId());
            dto.setDetail(ol.getDetail());
            dto.setTimestamp(ol.getTimestamp() != null
                ? ol.getTimestamp().toLocalDateTime()
                : null);
            dto.setResult(AuditLogDTO.textResult(ol.getResult()));
            return dto;
        }).collect(Collectors.toList());

        Page<AuditLogDTO> out = new Page<>(pageResult.getCurrent(), pageResult.getSize(), pageResult.getTotal());
        out.setRecords(dtos);
        return out;
    }

    /** 列表与导出共用的过滤条件 —— 两边的口径必须一致，否则"导出的"和"看到的"对不上。 */
    private LambdaQueryWrapper<OperationLog> buildFilter(
            Long userId, String operationType,
            LocalDateTime startTime, LocalDateTime endTime,
            String result, String resourceType) {
        LambdaQueryWrapper<OperationLog> wrapper = new LambdaQueryWrapper<>();
        if (userId != null) wrapper.eq(OperationLog::getUserId, userId);
        if (operationType != null && !operationType.isBlank()) {
            Set<String> stored = AuditOpType.storedValuesFor(operationType);
            if (stored.size() == 1) {
                wrapper.eq(OperationLog::getOperationType, operationType);
            } else {
                wrapper.in(OperationLog::getOperationType, stored);
            }
        }
        if (result != null && !result.isBlank()) {
            wrapper.eq(OperationLog::getResult, parseResult(result));
        }
        if (resourceType != null && !resourceType.isBlank()) {
            // 同时匹配历史遗留的大写写法（Document），否则老记录查不出来
            String lower = resourceType.trim().toLowerCase(java.util.Locale.ROOT);
            if (!lower.equals(resourceType)) {
                wrapper.and(w -> w.eq(OperationLog::getResourceType, lower)
                    .or().eq(OperationLog::getResourceType, resourceType));
            } else {
                wrapper.eq(OperationLog::getResourceType, lower);
            }
        }
        if (startTime != null) wrapper.ge(OperationLog::getTimestamp, startTime);
        if (endTime != null) wrapper.le(OperationLog::getTimestamp, endTime);
        return wrapper;
    }

    // ==================================================================
    // 导出（2026-11-08 新增）
    // ==================================================================

    /** 单次导出的行数上限 —— 超过就要求缩小时间范围，不做无限制的大导出。 */
    public static final int MAX_EXPORT_ROWS = 50_000;

    /**
     * 导出审计日志（CSV，UTF-8 BOM）。
     *
     * <p><b>为什么是同步而不是异步</b>：契约里写的是"异步生成 + 签名 URL 下载"，
     * 但仓库里**没有任何导出任务机制**（没有任务表、没有调度器）——
     * 原实现返回的是一个指向 {@code api.example.com} 的**伪造 URL**，
     * 点开永远是 404。与其留一个骗人的异步接口，不如同步导：
     * 上限 {@link #MAX_EXPORT_ROWS} 行，够用且不会把内存和连接池打满。
     *
     * <p><b>CSV 注入防护</b>：单元格以 {@code = + - @ } 开头时，
     * Excel 会当成公式执行。审计的 detail 字段是**用户可控**的
     * （比如文件名、理由），所以这类单元格一律加前缀 {@code '} 打断公式。
     */
    public byte[] exportCsv(Long userId, String operationType,
                             LocalDateTime startTime, LocalDateTime endTime,
                             String result, String resourceType) {
        LambdaQueryWrapper<OperationLog> wrapper = buildFilter(
            userId, operationType, startTime, endTime, result, resourceType);
        wrapper.orderByDesc(OperationLog::getTimestamp);

        Page<OperationLog> page = new Page<>(1, MAX_EXPORT_ROWS + 1);
        page.setSearchCount(false);
        // **必须显式覆盖 maxLimit**（2026-11-11 审计第四轮 §1）：
        // 分页插件的全局 maxLimit=1000 会把这次查询截断成 1000 行 ——
        // 那样"超过 5 万行就报错"的保护就变成了"最多只能导出 1000 行，
        // 而且还不报错"（静默截断，用户以为导全了）。
        // Page.setMaxLimit 的优先级高于拦截器的全局值（实测：50001 时取到全部行）。
        // 这样导出的内存占用也是有界的：最多 MAX_EXPORT_ROWS+1 行，
        // 而不是"整个 operation_log 表"（插件缺失时就是这个行为）。
        page.setMaxLimit((long) MAX_EXPORT_ROWS + 1);
        List<OperationLog> records = operationLogRepository.selectPage(page, wrapper).getRecords();
        if (records.size() > MAX_EXPORT_ROWS) {
            throw new IllegalArgumentException(
                "导出结果超过 " + MAX_EXPORT_ROWS + " 行，请缩小时间范围后再试");
        }

        Set<Long> userIds = records.stream()
            .map(OperationLog::getUserId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, String> empNoMap = Collections.emptyMap();
        if (!userIds.isEmpty()) {
            empNoMap = sysUserRepository.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, SysUser::getEmployeeNo,
                    (a, b) -> a, HashMap::new));
        }

        StringBuilder sb = new StringBuilder();
        // BOM —— 没有它 Excel 打开中文全是乱码
        sb.append('﻿');
        sb.append("时间,工号,操作类型,资源类型,资源ID,结果,HTTP方法,端点,状态码,耗时(ms),IP,User-Agent,详情");
        sb.append('\n');
        for (OperationLog ol : records) {
            sb.append(csvCell(ol.getTimestamp() != null
                ? ol.getTimestamp().toLocalDateTime().toString() : "")).append(',')
              .append(csvCell(empNoMap.get(ol.getUserId()))).append(',')
              .append(csvCell(AuditOpType.normalize(ol.getOperationType()))).append(',')
              .append(csvCell(normalizeResourceType(ol.getResourceType()))).append(',')
              .append(csvCell(ol.getResourceId() == null ? "" : String.valueOf(ol.getResourceId()))).append(',')
              .append(csvCell(AuditLogDTO.textResult(ol.getResult()))).append(',')
              .append(csvCell(ol.getHttpMethod())).append(',')
              .append(csvCell(ol.getEndpoint())).append(',')
              .append(csvCell(ol.getStatusCode() == null ? "" : String.valueOf(ol.getStatusCode()))).append(',')
              .append(csvCell(ol.getDurationMs() == null ? "" : String.valueOf(ol.getDurationMs()))).append(',')
              .append(csvCell(ol.getIp())).append(',')
              .append(csvCell(ol.getUserAgent())).append(',')
              .append(csvCell(ol.getDetail()))
              .append('\n');
        }
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** CSV 单元格转义 + 公式注入防护。 */
    private static String csvCell(String raw) {
        if (raw == null) {
            return "";
        }
        String v = raw;
        // 去掉会截断单元格的换行
        v = v.replace("\r", " ").replace("\n", " ");
        // 公式注入：以 = + - @ 开头的单元格，Excel 会当公式跑
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    /**
     * 全部资源类型（审计页筛选下拉用）。
     *
     * <p>从库里 DISTINCT 出来，而不是写死清单 —— 资源类型会随业务增长，
     * 写死的清单会让新类型在审计页上查不出来。
     */
    public List<String> distinctResourceTypes() {
        return operationLogRepository
            .selectObjs(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<OperationLog>()
                .select("DISTINCT resource_type")
                .isNotNull("resource_type")
                .ne("resource_type", "")
                .orderByAsc("resource_type"))
            .stream()
            .map(String::valueOf)
            .map(this::normalizeResourceType)
            .distinct()
            .sorted()
            .collect(Collectors.toList());
    }
}