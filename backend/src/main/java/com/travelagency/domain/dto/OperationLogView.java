package com.travelagency.domain.dto;

import com.travelagency.domain.entity.OperationLog;

import java.time.LocalDateTime;

/**
 * 操作日志对外视图，对齐契约 OperationLog（additionalProperties: false）。
 *
 * <p>两点与持久化实体不同：
 * <ul>
 *   <li>实体没有操作人姓名，但契约把 {@code operatorName} 列为 required，
 *       需要按 {@code operatorId} 查 {@code sys_user} 补齐；</li>
 *   <li>实体继承 {@code BaseEntity} 带出 {@code updatedAt}，而契约的属性表里没有该字段，
 *       在 {@code additionalProperties: false} 下必须去掉。</li>
 * </ul></p>
 */
public record OperationLogView(
        Long id,
        Long operatorId,
        String operatorName,
        String module,
        String operationType,
        String objectType,
        String objectId,
        String result,
        String detail,
        String ipAddress,
        LocalDateTime createdAt) {

    public static OperationLogView from(OperationLog log, String operatorName) {
        if (log == null) {
            return null;
        }
        return new OperationLogView(log.id, log.operatorId, operatorName, log.module,
                log.operationType, log.objectType, log.objectId, log.result, log.detail,
                log.ipAddress, log.createdAt);
    }
}
