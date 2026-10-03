package com.travelagency.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 列表元素不得重复，对应契约里 {@code uniqueItems: true} 的数组字段（目前是酒店的
 * {@code facilities}）。
 *
 * <p><b>为什么要有这一层，而不是只在服务层判</b>：服务层抛出的 {@code BusinessException}
 * 经 {@code GlobalExceptionHandler.handleBusiness} 只回 {@code {code, message}}，
 * {@code errors[]} 是空的 —— 调用方拿不到"哪个字段错了"的定位信息，前端也没法把这个错误
 * 就地显示到对应的输入框上。把 {@code uniqueItems} 放在字段约束里，它就和其它字段规则一样
 * 进 {@code errors[]}（{@code field = facilities}），而服务层仍然保留同一份判定作为兜底。</p>
 */
@Documented
@Constraint(validatedBy = UniqueElementsValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface UniqueElements {

    String message() default "列表不能包含重复取值";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
