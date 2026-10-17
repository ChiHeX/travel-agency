package com.travelagency.domain.service;

import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.entity.Payment;
import com.travelagency.domain.entity.TravelOrder;
import com.travelagency.domain.mapper.PaymentMapper;
import com.travelagency.domain.mapper.TravelOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LocalPaymentServiceTest {
    private final TravelOrderMapper orders = mock(TravelOrderMapper.class);
    private final PaymentMapper payments = mock(PaymentMapper.class);
    private final OrderService orderService = mock(OrderService.class);
    private final LocalPaymentService service = new LocalPaymentService(orders, payments, orderService, "127.0.0.1");
    private TravelOrder order;
    private Payment payment;

    @BeforeEach
    void setup() {
        order = new TravelOrder();
        order.id = 1L; order.userId = 2L; order.orderNo = "TEST-ORDER";
        order.status = "WAIT_PAY"; order.paymentStatus = "UNPAID"; order.totalAmount = new BigDecimal("123.45");
        payment = new Payment();
        payment.id = 3L; payment.orderId = order.id; payment.paymentNo = "TEST-PAY";
        payment.status = "UNPAID"; payment.channel = "ALIPAY_SANDBOX"; payment.amount = order.totalAmount;
        when(orders.selectOne(any())).thenReturn(order);
        when(payments.selectOne(any())).thenReturn(payment);
        when(payments.selectById(3L)).thenReturn(payment);
    }

    @Test
    void paysUsingServerAmountAndDistinctChannelThenReplaysWithoutAnotherWrite() {
        doAnswer(invocation -> { payment.status = "PAID"; order.status = "PAID_WAIT_CONFIRM"; return null; })
                .when(orderService).markPaid("TEST-ORDER", "LOCAL-TEST-PAY", order.totalAmount);
        assertEquals("LOCAL_SIMULATION", service.pay("TEST-ORDER", 2L).channel());
        assertEquals("PAID", service.pay("TEST-ORDER", 2L).status());
        verify(payments, times(1)).updateById(payment);
        verify(orderService, times(1)).markPaid("TEST-ORDER", "LOCAL-TEST-PAY", new BigDecimal("123.45"));
    }

    @Test
    void deniesOtherUsersEvenWhenPaymentAlreadySucceeded() {
        payment.status = "PAID"; payment.channel = "LOCAL_SIMULATION";
        assertEquals(403, assertThrows(BusinessException.class, () -> service.pay("TEST-ORDER", 99L)).getStatus());
        verifyNoInteractions(orderService);
        verify(payments, never()).updateById(any(Payment.class));
    }

    @Test
    void refusesCancelledOrdersAndAlreadyStartedAlipayPayments() {
        order.status = "CANCELLED";
        assertThrows(BusinessException.class, () -> service.pay("TEST-ORDER", 2L));
        order.status = "WAIT_PAY"; payment.status = "PENDING";
        assertThrows(BusinessException.class, () -> service.pay("TEST-ORDER", 2L));
        verifyNoInteractions(orderService);
        verify(payments, never()).updateById(any(Payment.class));
    }

    @Test
    void rejectsAmountMismatch() {
        payment.amount = BigDecimal.ONE;
        assertEquals("PAYMENT_AMOUNT_MISMATCH", assertThrows(BusinessException.class,
                () -> service.pay("TEST-ORDER", 2L)).getCode());
        verifyNoInteractions(orderService);
    }

    @Test
    void refusesPublicOrUnspecifiedBinding() {
        for (String address : new String[]{"", "0.0.0.0", "192.168.1.2"}) {
            assertThrows(IllegalStateException.class, () -> new LocalPaymentService(orders, payments, orderService, address));
        }
    }

    @Test
    void onlyExplicitLocalProfileRegistersServiceAndProductionAlwaysDisablesIt() {
        for (String[] profiles : new String[][]{{}, {"local-payment"}, {"local-payment", "prod"}, {"local-payment", "production"}}) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().setActiveProfiles(profiles);
                context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                        "test", java.util.Map.of("server.address", "127.0.0.1")));
                context.registerBean(TravelOrderMapper.class, () -> orders);
                context.registerBean(PaymentMapper.class, () -> payments);
                context.registerBean(OrderService.class, () -> orderService);
                context.register(LocalPaymentService.class,
                        com.travelagency.web.controller.LocalPaymentController.class,
                        com.travelagency.web.controller.PaymentOptionsController.class);
                context.refresh();
                assertEquals(profiles.length == 1, !context.getBeansOfType(LocalPaymentService.class).isEmpty());
                assertEquals(profiles.length == 1, !context.getBeansOfType(
                        com.travelagency.web.controller.LocalPaymentController.class).isEmpty());
                assertEquals(profiles.length == 1, context.getBean(
                        com.travelagency.web.controller.PaymentOptionsController.class).options().data().localSimulationEnabled());
            }
        }
    }
}
