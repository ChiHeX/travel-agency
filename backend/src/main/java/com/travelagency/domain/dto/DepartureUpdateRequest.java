package com.travelagency.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 修改团期请求，对齐契约 {@code DepartureUpdateRequest}（additionalProperties: false）。
 *
 * <p>字段与 {@link DepartureCreateRequest} 相同，额外要求回传<b>读取时拿到的</b>
 * {@code version}：后端执行 {@code UPDATE ... WHERE id = ? AND version = ?}，
 * 成功后把版本加一；影响 0 行且当前版本不等于提交版本时返回
 * {@code 409 DEPARTURE_VERSION_CONFLICT}。</p>
 *
 * <p>这样解决的是"两位工作人员各自打开同一条团期，先后保存，后保存的人无意覆盖了前一位的
 * 日期 / 价格 / 导游改动"。注意版本号只负责发现"基于过期数据提交"，
 * <b>不替代名额校验</b>——名额仍由同一条语句里的
 * {@code reserved_people + confirmed_people <= maxPeople} 条件保证，
 * 因为名额变化来自下单链路，与"编辑是否过期"是两件事。</p>
 */
public record DepartureUpdateRequest(
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
        Long guideId,
        @NotNull(message = "团期版本号不能为空")
        @Min(value = 0, message = "团期版本号不能为负数") Integer version) {

    /** 可编辑字段部分，供服务层复用创建请求的同一套校验与赋值逻辑。 */
    public DepartureCreateRequest editableFields() {
        return new DepartureCreateRequest(routeId, startDate, endDate, adultPrice, childPrice, maxPeople, guideId);
    }
}
