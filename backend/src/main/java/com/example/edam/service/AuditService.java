package com.example.edam.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edam.dto.AuditLogDTO;
import com.example.edam.model.OperationLog;
import com.example.edam.model.SysUser;
import com.example.edam.repository.OperationLogRepository;
import com.example.edam.repository.SysUserRepository;
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

    private final OperationLogRepository operationLogRepository;
    private final SysUserRepository sysUserRepository;

    // ============== 写入 ==============

    @Async
    public void log(Long userId, String operationType, String resourceType,
                    Long resourceId, String result) {
        try {
            OperationLog entry = new OperationLog();
            entry.setUserId(userId);
            entry.setOperationType(operationType);
            entry.setResourceType(resourceType != null ? resourceType : "system");
            entry.setResourceId(resourceId);
            entry.setResult(parseResult(result));
            entry.setTimestamp(java.time.OffsetDateTime.now());
            operationLogRepository.insert(entry);
        } catch (Exception e) {
            log.error("audit_log_write_failed, user_id={}, op={}", userId, operationType, e);
        }
    }

    public void log(Long userId, String operationType, String resourceType,
                    Long resourceId, String result, String detail, String ip, String userAgent) {
        try {
            OperationLog entry = new OperationLog();
            entry.setUserId(userId);
            entry.setOperationType(operationType);
            entry.setResourceType(resourceType != null ? resourceType : "system");
            entry.setResourceId(resourceId);
            entry.setResult(parseResult(result));
            entry.setDetail(detail);
            entry.setIp(ip);
            entry.setUserAgent(userAgent);
            entry.setTimestamp(java.time.OffsetDateTime.now());
            operationLogRepository.insert(entry);
        } catch (Exception e) {
            log.error("audit_log_write_failed, user_id={}, op={}", userId, operationType, e);
        }
    }

    private Integer parseResult(String result) {
        if ("success".equals(result)) return 1;
        if ("failure".equals(result)) return 2;
        if ("denied".equals(result)) return 3;
        return 2;
    }

    // ============== 查询 ==============

    /**
     * 列表（分页 + 过滤），JOIN sys_user 取 employee_no
     */
    public Page<AuditLogDTO> list(int page, int pageSize,
                                  Long userId, String operationType,
                                  LocalDateTime startTime, LocalDateTime endTime) {

        LambdaQueryWrapper<OperationLog> wrapper = new LambdaQueryWrapper<>();
        if (userId != null) wrapper.eq(OperationLog::getUserId, userId);
        if (operationType != null && !operationType.isBlank()) {
            wrapper.eq(OperationLog::getOperationType, operationType);
        }
        if (startTime != null) wrapper.ge(OperationLog::getTimestamp, startTime);
        if (endTime != null) wrapper.le(OperationLog::getTimestamp, endTime);
        wrapper.orderByDesc(OperationLog::getTimestamp);

        Page<OperationLog> pageResult = operationLogRepository.selectPage(
            new Page<>(page, pageSize), wrapper);

        // 收集 userId → batch 查 sys_user
        Set<Long> userIds = pageResult.getRecords().stream()
            .map(OperationLog::getUserId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, String> empNoMap = Collections.emptyMap();
        if (!userIds.isEmpty()) {
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
            dto.setOperationType(ol.getOperationType());
            dto.setResourceType(ol.getResourceType());
            dto.setResourceId(ol.getResourceId());
            dto.setIp(ol.getIp());
            dto.setUserAgent(ol.getUserAgent());
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
}