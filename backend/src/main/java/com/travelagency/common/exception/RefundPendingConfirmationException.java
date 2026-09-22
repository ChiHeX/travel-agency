package com.travelagency.common.exception;

/**
 * 出款结果<b>尚未确认</b>：退款请求已经发给支付宝，但没能拿到「钱到底退没退」的确定结论。
 *
 * <p>它同时承担两件事，缺一不可：</p>
 * <ol>
 *   <li><b>对外的响应</b>：继承 {@link BusinessException} ⇒ 全局异常处理器按
 *       {@code 503 REFUND_RESULT_UNCONFIRMED} 返回。与明确失败的 {@code REFUND_FAILED} 分开，
 *       是因为两者的处置不同：失败可以原样重试也可以拒绝，未确认则<b>只能继续确认</b>。</li>
 *   <li><b>对事务的指令</b>：{@code OrderService.approveRefund} 的
 *       {@code @Transactional(noRollbackFor = RefundPendingConfirmationException.class)}
 *       让本异常<b>不回滚</b>。退款单因此留在 {@code PROCESSING}（待确认）而不是退回
 *       {@code APPLYING} —— 后者会把「拒绝」变成一个危险的出路：拒绝分支会恢复订单原状态，
 *       于是当支付宝侧其实已经退款成功时，会出现「钱退了、后台显示退款被拒、订单仍已支付」，
 *       事后无法自证，也无法靠重试恢复。</li>
 * </ol>
 *
 * <p>注意「不发生回滚」与「没有副作用」是两回事：这里提交的副作用只有退款单的
 * {@code status=PROCESSING} 与审核人/审核时间留痕。释放名额、改动订单与支付单、回退线路
 * 有效报名数全部排在出款成功之后，未确认时一处都不会执行。</p>
 */
public class RefundPendingConfirmationException extends BusinessException {

    /** 与 {@code AlipayGatewayClient.RefundResult.unconfirmed(String)} 使用的原因码保持一致。 */
    public static final String CODE = "REFUND_RESULT_UNCONFIRMED";

    public RefundPendingConfirmationException(String message) {
        super(503, CODE, message);
    }
}
