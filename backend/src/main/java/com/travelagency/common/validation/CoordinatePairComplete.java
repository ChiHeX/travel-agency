package com.travelagency.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 经纬度必须成对提供：要么都填，要么都留空，不能只填一个。
 *
 * <p>为什么不允许"半截坐标"：用户端地图（{@code MapPreview}）只在经纬度<b>都非空</b>时落点，
 * 只填一个的点会被静默丢弃 —— 运营以为录入了位置，地图上却什么都没有，且没有任何提示。
 * 与其让数据悄悄失效，不如在写入前以 422 拒绝。</p>
 *
 * <p>对应契约里 {@code AttractionUpsertRequest} / {@code HotelCreateRequest} /
 * {@code HotelUpdateRequest} / {@code ItineraryItemRequest} 共同引用的
 * {@code CoordinatePairRule}："提供了经度就必须提供纬度"（字段缺失与显式 {@code null} 等价）。
 * 约束作用于类型级，配合
 * {@link HasCoordinatePair} 读取坐标。{@code null} 值本身不判错：两个都为空是合法的
 * （表示未录入坐标）。</p>
 */
@Documented
@Constraint(validatedBy = CoordinatePairValidator.class)
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface CoordinatePairComplete {

    String message() default "经度和纬度需要同时填写，或同时留空";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
