package com.example.edam.service;

import com.example.edam.model.OperationLog;
import com.example.edam.repository.OperationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * 审计日志 Service
 * 异步写入 operation_log 表（按月分表）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final OperationLogRepository operationLogRepository;

    /**
     * 异步记录操作日志
     */
    @Async
    public void log(Long userId, String operationType, String resourceType,
                    Long resourceId, String result) {
        try {
            OperationLog entry = new OperationLog();
            entry.setUserId(userId);
            entry.setOperationType(operationType);
            entry.setResourceType(resourceType != null ? resourceType : "system");
            entry.setResourceId(resourceId);
            entry.setResult("success".equals(result) ? 1 : "failure".equals(result) ? 2 : 3);
            entry.setTimestamp(OffsetDateTime.now());
            operationLogRepository.insert(entry);
        } catch (Exception e) {
            log.error("audit_log_write_failed, user_id={}, op={}", userId, operationType, e);
        }
    }

    public void log(Long userId, String operationType, String resourceType,
                    Long resourceId, String result, String detail) {
        try {
            OperationLog entry = new OperationLog();
            entry.setUserId(userId);
            entry.setOperationType(operationType);
            entry.setResourceType(resourceType != null ? resourceType : "system");
            entry.setResourceId(resourceId);
            entry.setResult("success".equals(result) ? 1 : "failure".equals(result) ? 2 : 3);
            entry.setDetail(detail);
            entry.setTimestamp(OffsetDateTime.now());
            operationLogRepository.insert(entry);
        } catch (Exception e) {
            log.error("audit_log_write_failed, user_id={}, op={}", userId, operationType, e);
        }
    }
}