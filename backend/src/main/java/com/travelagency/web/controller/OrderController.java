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
import org.springframework.web.bind.annotation.RequestHeader;
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

    /**
     * 创建订单。
     * 契约把 Idempotency-Key 标为必填：同一用户携带同一键重试时只会真正下单一次，
     * 不会重复占用团期名额或产生第二张支付单。
     */
    @PostMapping
    public ResponseEntity<ApiResponse<OrderView>> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        OrderView order = orderService.create(CurrentUser.required().userId(), request, idempotencyKey);
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

    /**
     * 取消待支付订单。契约的 200 响应是 OrderEnvelope（data 为取消后的订单），
     * 而不是空 data。
     */
    @PostMapping("/{orderNo}/cancel")
    public ApiResponse<OrderView> cancel(@PathVariable String orderNo) {
        return ApiResponse.ok(orderService.cancel(orderNo, CurrentUser.required().userId()));
    }

    /**
     * 申请退款。契约同样把 Idempotency-Key 标为必填。
     */
    @PostMapping("/{orderNo}/refunds")
    public ResponseEntity<ApiResponse<RefundView>> refund(
            @PathVariable String orderNo,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RefundRequest request) {
        RefundView refund = orderService.applyRefund(orderNo, CurrentUser.required().userId(), request, idempotencyKey);
        return ResponseEntity.created(URI.create("/api/orders/" + orderNo + "/refunds")).body(ApiResponse.ok(refund));
    }

    @PostMapping("/{orderNo}/reviews")
    public ResponseEntity<ApiResponse<ReviewView>> review(
            @PathVariable String orderNo, @Valid @RequestBody ReviewRequest request) {
        ReviewView review = orderService.review(orderNo, CurrentUser.required().userId(), request);
        return ResponseEntity.created(URI.create("/api/orders/" + orderNo + "/reviews")).body(ApiResponse.ok(review));
    }
}
