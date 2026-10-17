package com.travelagency.web.controller;

import com.travelagency.common.api.ApiResponse;
import com.travelagency.common.security.CurrentUser;
import com.travelagency.domain.dto.PaymentView;
import com.travelagency.domain.service.LocalPaymentService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments/local")
@Profile("local-payment & !prod & !production")
public class LocalPaymentController {
    private final LocalPaymentService payments;

    public LocalPaymentController(LocalPaymentService payments) { this.payments = payments; }

    @PostMapping("/{orderNo}")
    public ApiResponse<PaymentView> pay(@PathVariable String orderNo) {
        return ApiResponse.ok(payments.pay(orderNo, CurrentUser.required().userId()));
    }
}
