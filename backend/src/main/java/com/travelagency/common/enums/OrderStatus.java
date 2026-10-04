package com.travelagency.common.enums;

/**
 * 订单状态，取值对齐契约 {@code OrderStatus} 枚举
 * [WAIT_PAY, PAID_WAIT_CONFIRM, CONFIRMED, TRAVELLING, COMPLETED, CANCELLED,
 * REFUND_APPLYING, REFUND_PROCESSING, REFUNDED, REFUND_REJECTED]。
 *
 * <p>其中 {@link #REFUND_PROCESSING} 与 {@link #REFUND_REJECTED} 是<b>契约保留值</b>：
 * 实现里没有任何路径把订单写成这两个状态，保留它们只是为了与冻结契约的枚举一致。
 * 退款的「处理中」记在退款单上（{@code RefundStatus.PROCESSING}，出款已发起但结果未确认的持久态），
 * 拒绝则用 {@code refund.original_order_status} 把订单恢复成申请前的业务状态。
 * 订单一旦离开 {@link #REFUND_APPLYING}，只可能变成 {@link #REFUNDED}（出款成功）
 * 或回到申请前的业务状态（拒绝）。</p>
 *
 * <p>要真正落库这两个状态，必须同时更新 {@code docs/ARCHITECTURE.md} 的「订单状态机」、
 * {@code docs/PRD.md} §12 与 {@code OrderStatusReservedValuesTest} —— 那个测试就是用来
 * 拦住"悄悄改变状态机、文档却还写着旧流程"的。</p>
 */
public final class OrderStatus {
    public static final String WAIT_PAY = "WAIT_PAY";
    public static final String PAID_WAIT_CONFIRM = "PAID_WAIT_CONFIRM";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String TRAVELLING = "TRAVELLING";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";
    public static final String REFUND_APPLYING = "REFUND_APPLYING";

    /** 契约保留值：实现不写入订单，退款的"处理中"记在 {@code refund.status} 上。 */
    public static final String REFUND_PROCESSING = "REFUND_PROCESSING";

    public static final String REFUNDED = "REFUNDED";

    /** 契约保留值：拒绝退款时订单直接恢复成申请前的业务状态，不经过这个值。 */
    public static final String REFUND_REJECTED = "REFUND_REJECTED";

    private OrderStatus() {
    }
}
