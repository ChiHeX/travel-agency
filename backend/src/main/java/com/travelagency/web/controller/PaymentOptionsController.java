package com.travelagency.web.controller;

import com.travelagency.common.api.ApiResponse;
import com.travelagency.domain.dto.PaymentOptionsView;
import com.travelagency.domain.service.LocalPaymentService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentOptionsController {
    private final ObjectProvider<LocalPaymentService> localPayments;

    public PaymentOptionsController(ObjectProvider<LocalPaymentService> localPayments) {
        this.localPayments = localPayments;
    }

    @GetMapping("/api/payments/options")
    public ApiResponse<PaymentOptionsView> options() {
        return ApiResponse.ok(new PaymentOptionsView(localPayments.getIfAvailable() != null));
    }
}
