package com.travelagency.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 住宿安排的类型与字段必须自洽（契约 {@code ItineraryDayRequest} 的住宿规则）：
 *
 * <ul>
 *   <li>{@code HOTEL}：必须提供 {@code hotelId}；</li>
 *   <li>{@code STANDARD}：必须提供非空的 {@code accommodationStandard}，且 {@code hotelId} 为空；</li>
 *   <li>{@code NONE} / {@code PENDING}：{@code hotelId} 必须为空。</li>
 * </ul>
 *
 * <p>未提交 {@code accommodationType} 时先按 {@code hotelId} 推断
 * （非空 → {@code HOTEL}，为空 → {@code PENDING}），再套用上面的规则，
 * 与存量数据的迁移口径一致；<b>推断永远不会得出 {@code NONE}</b>。</p>
 *
 * <p>为什么必须拦在写入之前：{@code accommodationType} 一旦与 {@code hotelId} 不一致，
 * 用户端要么显示一家其实没有安排的酒店，要么把"指定了酒店"的当天显示成"不含住宿"，
 * 两种都是对外可见的错误信息，且数据库层面拦不住（外键只管 hotelId 是否存在）。</p>
 *
 * <p>“指定的酒店必须存在且启用”这条依赖数据库状态，由服务层在事务内以 422 处理，
 * 不在本约束里判断。</p>
 */
@Documented
@Constraint(validatedBy = AccommodationConsistentValidator.class)
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AccommodationConsistent {

    String message() default "住宿安排与酒店关联不一致";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
