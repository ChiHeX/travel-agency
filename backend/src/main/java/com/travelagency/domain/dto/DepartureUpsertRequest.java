package com.travelagency.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 团期新增/修改请求，对齐契约 {@code DepartureUpsertRequest}（additionalProperties: false）。
 *
 * <p>只声明契约允许客户端提交的字段。{@code status}、{@code reservedPeople}、
 * {@code confirmedPeople}、{@code version} 不在契约内，且都由服务端决定：</p>
 * <ul>
 *   <li>{@code status} 只能通过 {@code PATCH /admin/departures/{departureId}/status} 流转，
 *       新建团期一律为 {@code DRAFT}；</li>
 *   <li>{@code reservedPeople} / {@code confirmedPeople} 是下单与支付链路维护的名额计数，
 *       客户端可写会让"剩余名额"直接失真；</li>
 *   <li>{@code version} 是并发控制字段。</li>
 * </ul>
 *
 * <p>这些字段一旦出现在请求体里，会被全局 {@code FAIL_ON_UNKNOWN_PROPERTIES} 配置
 * 按契约的 {@code additionalProperties: false} 拒绝（400），从根上杜绝越权写入。</p>
 *
 * <p>金额按契约 {@code Money} 接收（十进制字符串，如 {@code "2999.00"}）。
 * {@code @Digits(integer = 10, fraction = 2)} 与数据库 {@code DECIMAL(12,2)} 对齐：
 * 不加这一层，超出精度的金额会在写库时抛异常并以 500 返回，而不是可定位的 422。</p>
 */
public record DepartureUpsertRequest(
        @NotNull(message = "所属线路不能为空") Long routeId,
        @NotNull(message = "出发日期不能为空") LocalDate startDate,
        @NotNull(message = "返程日期不能为空") LocalDate endDate,
        @NotNull(message = "成人价格不能为空")
        @DecimalMin(value = "0", message = "成人价格不能为负数")
        @Digits(integer = 10, fraction = 2, message = "成人价格最多 10 位整数与 2 位小数") BigDecimal adultPrice,
        @NotNull(message = "儿童价格不能为空")
        @DecimalMin(value = "0", message = "儿童价格不能为负数")
        @Digits(integer = 10, fraction = 2, message = "儿童价格最多 10 位整数与 2 位小数") BigDecimal childPrice,
        @NotNull(message = "最大人数不能为空")
        @Min(value = 1, message = "最大人数不能小于 1") Integer maxPeople,
        Long guideId) {
}
