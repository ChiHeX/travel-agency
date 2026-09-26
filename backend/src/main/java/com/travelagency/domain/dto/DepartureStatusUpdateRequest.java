package com.travelagency.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 团期运营状态变更请求，对齐契约 {@code DepartureStatusUpdateRequest}。
 *
 * <p>后台允许把团期改成契约 {@code DepartureStatus} 中的任意取值（含人工下架、取消），
 * 因此这里不限制"只能从某状态迁到某状态"：状态机约束只加在导游端的
 * {@code POST /guide/departures/{departureId}/start|complete} 上。</p>
 *
 * <p>取值由 {@code @Pattern} 在进入业务逻辑前拦下并按契约返回 422，
 * 且错误项带 {@code field=status}，前端可以直接定位到该输入项。
 * {@code DepartureService#changeStatus} 内部的枚举白名单是防止绕过请求校验的调用方
 * （例如直接调用服务的测试或其它服务）写入非法状态的兜底。</p>
 */
public record DepartureStatusUpdateRequest(
        @NotBlank(message = "团期状态不能为空")
        @Pattern(regexp = "DRAFT|OPEN|FULL|CLOSED|TRAVELLING|FINISHED|CANCELLED",
                message = "团期状态只能是 DRAFT、OPEN、FULL、CLOSED、TRAVELLING、FINISHED 或 CANCELLED")
        String status) {
}
