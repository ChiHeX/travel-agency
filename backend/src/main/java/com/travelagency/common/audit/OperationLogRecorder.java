package com.travelagency.common.audit;

import com.travelagency.domain.entity.OperationLog;
import com.travelagency.domain.mapper.OperationLogMapper;
import org.springframework.stereotype.Component;

/**
 * 操作日志记录器：把后台写操作写入 {@code operation_log}，供 GET /admin/logs 查询与追溯。
 *
 * <p>原先各 Controller 各自维护一份私有 log 方法，这里集中成一个组件，避免多处重复实现
 * 以及字段填写口径不一致。</p>
 *
 * <p><b>必须在业务事务内调用</b>：调用方是 Service 的 {@code @Transactional} 写方法，
 * 而不是 Controller。这样"业务数据写入"和"操作日志写入"处于同一个事务：
 * 日志写失败时业务修改一并回滚，不会出现"接口报错但数据已经改掉"的情况；
 * 反过来业务失败时也不会留下没有对应操作的孤立日志。</p>
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
     * @param operatorId    操作人主键，由 Controller 从登录上下文取出后显式传入，
     *                      避免审计组件依赖请求线程的安全上下文（也便于单元测试）
     * @param module        业务模块，例如"线路"、"行程"
     * @param operationType 操作类型，例如 CREATE / UPDATE / STATUS / DELETE
     * @param objectType    对象类型，例如 ROUTE / ITINERARY_DAY / ITINERARY_ITEM
     * @param objectId      对象主键，可为 null
     * @param detail        可读的操作说明，不得包含密码、Token 等敏感信息
     */
    public void record(Long operatorId, String module, String operationType, String objectType,
                       Object objectId, String detail) {
        OperationLog log = new OperationLog();
        log.operatorId = operatorId;
        log.module = module;
        log.operationType = operationType;
        log.objectType = objectType;
        log.objectId = objectId == null ? null : String.valueOf(objectId);
        log.result = "SUCCESS";
        log.detail = detail;
        operationLogMapper.insert(log);
    }
}
