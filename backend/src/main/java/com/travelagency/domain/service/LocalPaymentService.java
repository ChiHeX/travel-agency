package com.travelagency.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travelagency.common.enums.OrderStatus;
import com.travelagency.common.enums.PaymentStatus;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.PaymentView;
import com.travelagency.domain.entity.Payment;
import com.travelagency.domain.entity.TravelOrder;
import com.travelagency.domain.mapper.PaymentMapper;
import com.travelagency.domain.mapper.TravelOrderMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("local-payment & !prod & !production")
public class LocalPaymentService {
    public static final String CHANNEL = "LOCAL_SIMULATION";
    private final TravelOrderMapper orders;
    private final PaymentMapper payments;
    private final OrderService orderService;

    public LocalPaymentService(TravelOrderMapper orders, PaymentMapper payments, OrderService orderService,
                               @Value("${server.address:}") String address) {
        if (!"127.0.0.1".equals(address) && !"::1".equals(address)) {
            throw new IllegalStateException("本地模拟支付必须绑定回环地址，请使用 local-payment profile 的默认 server.address");
        }
        this.orders = orders;
        this.payments = payments;
        this.orderService = orderService;
    }

    @Transactional
    public PaymentView pay(String orderNo, Long userId) {
        // Serialize retries and competing cancellation/payment requests on the same order.
        TravelOrder order = orders.selectOne(new QueryWrapper<TravelOrder>()
                .eq("order_no", orderNo).last("FOR UPDATE"));
        if (order == null) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "订单不存在");
        if (!order.userId.equals(userId)) throw new BusinessException(403, "ACCESS_DENIED", "无权支付该订单");
        Payment payment = payments.selectOne(new QueryWrapper<Payment>()
                .eq("order_id", order.id).last("FOR UPDATE"));
        if (payment == null) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "支付记录不存在");
        if (CHANNEL.equals(payment.channel) && PaymentStatus.PAID.equals(payment.status)) {
            return PaymentView.from(payment, orderNo);
        }
        if (!OrderStatus.WAIT_PAY.equals(order.status) || !PaymentStatus.UNPAID.equals(payment.status)
                || !PaymentStatus.UNPAID.equals(order.paymentStatus)) {
            throw new BusinessException(409, "ORDER_STATE_CONFLICT", "仅未发起支付的待付款订单可使用本地模拟支付");
        }
        if (payment.amount == null || payment.amount.compareTo(order.totalAmount) != 0) {
            throw new BusinessException(409, "PAYMENT_AMOUNT_MISMATCH", "支付记录金额与订单不一致");
        }
        payment.channel = CHANNEL;
        payments.updateById(payment);
        orderService.markPaid(orderNo, "LOCAL-" + payment.paymentNo, order.totalAmount);
        return PaymentView.from(payments.selectById(payment.id), orderNo);
    }
}
