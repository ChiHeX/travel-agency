package com.travelagency.web.controller;

import com.travelagency.common.api.ApiResponse;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.security.CurrentUser;
import com.travelagency.domain.dto.CreateOrderRequest;
import com.travelagency.domain.dto.OrderDetailResponse;
import com.travelagency.domain.dto.OrderSummaryView;
import com.travelagency.domain.dto.OrderView;
import com.travelagency.domain.dto.PaymentStartResponse;
import com.travelagency.domain.dto.RefundRequest;
import com.travelagency.domain.dto.RefundView;
import com.travelagency.domain.dto.ReviewRequest;
import com.travelagency.domain.dto.ReviewView;
import com.travelagency.domain.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<OrderView>> create(@Valid @RequestBody CreateOrderRequest request) {
        OrderView order = orderService.create(CurrentUser.required().userId(), request);
        return ResponseEntity.created(URI.create("/api/orders/" + order.orderNo())).body(ApiResponse.ok(order));
    }

    @GetMapping
    public ApiResponse<PageResponse<OrderSummaryView>> mine(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(orderService.listMine(CurrentUser.required().userId(), status, page, size));
    }

    @GetMapping("/{orderNo}")
    public ApiResponse<OrderDetailResponse> detail(@PathVariable String orderNo) {
        return ApiResponse.ok(orderService.detail(orderNo, CurrentUser.required()));
    }

    @PostMapping("/{orderNo}/pay")
    public ApiResponse<PaymentStartResponse> pay(@PathVariable String orderNo) {
        return ApiResponse.ok(orderService.startPayment(orderNo, CurrentUser.required().userId()));
    }

    @PostMapping("/{orderNo}/cancel")
    public ApiResponse<Void> cancel(@PathVariable String orderNo) {
        orderService.cancel(orderNo, CurrentUser.required().userId());
        return ApiResponse.ok();
    }

    @PostMapping("/{orderNo}/refunds")
    public ResponseEntity<ApiResponse<RefundView>> refund(
            @PathVariable String orderNo, @Valid @RequestBody RefundRequest request) {
        RefundView refund = orderService.applyRefund(orderNo, CurrentUser.required().userId(), request);
        return ResponseEntity.created(URI.create("/api/orders/" + orderNo + "/refunds")).body(ApiResponse.ok(refund));
    }

    @PostMapping("/{orderNo}/reviews")
    public ResponseEntity<ApiResponse<ReviewView>> review(
            @PathVariable String orderNo, @Valid @RequestBody ReviewRequest request) {
        ReviewView review = orderService.review(orderNo, CurrentUser.required().userId(), request);
        return ResponseEntity.created(URI.create("/api/orders/" + orderNo + "/reviews")).body(ApiResponse.ok(review));
    }
}
