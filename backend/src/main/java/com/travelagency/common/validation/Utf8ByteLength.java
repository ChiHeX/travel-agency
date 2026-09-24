package com.travelagency.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 约束字符串的 UTF-8 编码字节数上限。
 *
 * <p>Bean Validation 内建的 {@code @Size} 数的是 {@code String#length()}（UTF-16 code unit），
 * 而 BCrypt 等以字节为单位的算法数的是 UTF-8 字节，两者对非 ASCII 输入并不一致。
 * 需要按字节设限时用本注解，不要再用 {@code @Size(max = 72)} 代替。</p>
 *
 * <p>{@code null} 视为通过，判空交给 {@code @NotBlank}/{@code @NotNull}，避免同一个字段
 * 因为两个约束同时命中而出现两条互相矛盾的错误信息。</p>
 */
@Documented
@Constraint(validatedBy = Utf8ByteLengthValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER,
        ElementType.ANNOTATION_TYPE, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Utf8ByteLength {

    /** UTF-8 字节数上限（含）。 */
    int max();

    String message() default "UTF-8 编码长度不能超过 {max} 字节";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
