package com.travelagency.common.exception;

/**
 * 报名审核异常：确认报名时的业务复核没过（PRD §11.1 要求工作人员核对团期状态、剩余人数、
 * 游客实名信息完整性与订单信息）。
 *
 * <p>它同时承担两件事：</p>
 * <ol>
 *   <li><b>对外的响应</b>：继承 {@link BusinessException} ⇒ 全局异常处理器按
 *       {@code 409 ORDER_AUDIT_ANOMALY} 返回，并按 {@link #reasonCode()} 说明具体是哪一类异常。</li>
 *   <li><b>对事务的指令</b>：{@code OrderService.confirm} 上的
 *       {@code @Transactional(noRollbackFor = OrderAuditAnomalyException.class)} 让本异常<b>不回滚</b>。
 *       审核异常只可能发生在任何库存/统计写入<b>之前</b>，因此"不回滚"提交的只有两件事：
 *       一条审计留痕与一条站内通知 —— 这正是 PRD §29「订单审核异常」通知必须落地的东西。
 *       若让它回滚，通知会随之消失，用户就看不到异常说明了。</li>
 * </ol>
 */
public class OrderAuditAnomalyException extends BusinessException {

    /** 与契约错误码同源。 */
    public static final String CODE = "ORDER_AUDIT_ANOMALY";

    /** 支付状态未到账。 */
    public static final String PAYMENT_NOT_SETTLED = "PAYMENT_NOT_SETTLED";
    /** 出行人快照数量与订单登记人数不一致。 */
    public static final String TRAVELER_SNAPSHOT_MISMATCH = "TRAVELER_SNAPSHOT_MISMATCH";
    /** 出行人实名信息不完整（缺姓名 / 证件类型 / 证件号）。 */
    public static final String TRAVELER_IDENTITY_INCOMPLETE = "TRAVELER_IDENTITY_INCOMPLETE";

    private final String reasonCode;

    public OrderAuditAnomalyException(String reasonCode, String message) {
        super(409, CODE, message);
        this.reasonCode = reasonCode;
    }

    /** 机器可读的异常原因码，用于审计留痕与站内通知的去重键。 */
    public String reasonCode() {
        return reasonCode;
    }
}
