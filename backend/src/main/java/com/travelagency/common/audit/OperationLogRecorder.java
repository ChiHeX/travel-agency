package com.travelagency.common.audit;

import com.travelagency.common.security.CurrentUser;
import com.travelagency.domain.entity.OperationLog;
import com.travelagency.domain.mapper.OperationLogMapper;
import org.springframework.stereotype.Component;

/**
 * 操作日志记录器：把后台写操作写入 {@code operation_log}，供 GET /admin/logs 查询与追溯。
 *
 * <p>原先各 Controller 各自维护一份私有 log 方法，这里集中成一个组件，避免多处重复实现
 * 以及字段填写口径不一致。调用方必须处于已登录的后台请求上下文中（记录操作人）。</p>
 */
@Component
public class OperationLogRecorder {

    private final OperationLogMapper operationLogMapper;

    public OperationLogRecorder(OperationLogMapper operationLogMapper) {
        this.operationLogMapper = operationLogMapper;
    }

    /**
     * 记录一条后台操作日志。
     *
     * @param module        业务模块，例如"线路"、"行程"
     * @param operationType 操作类型，例如 CREATE / UPDATE / STATUS / DELETE
     * @param objectType    对象类型，例如 ROUTE / ITINERARY_DAY / ITINERARY_ITEM
     * @param objectId      对象主键，可为 null
     * @param detail        可读的操作说明，不得包含密码、Token 等敏感信息
     */
    public void record(String module, String operationType, String objectType, Object objectId, String detail) {
        OperationLog log = new OperationLog();
        log.operatorId = CurrentUser.required().userId();
        log.module = module;
        log.operationType = operationType;
        log.objectType = objectType;
        log.objectId = objectId == null ? null : String.valueOf(objectId);
        log.result = "SUCCESS";
        log.detail = detail;
        operationLogMapper.insert(log);
    }
}
