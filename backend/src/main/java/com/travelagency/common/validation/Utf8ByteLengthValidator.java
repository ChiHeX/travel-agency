package com.travelagency.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

/**
 * {@link Utf8ByteLength} 的实现。刻意<b>不</b>做任何截断：超限一律判失败，
 * 由全局异常处理转成 422 + {@code errors[]}，绝不静默裁剪后继续加密 ——
 * 静默截断会让「用户以为设了 A 密码、实际生效的是 A 的前 72 字节」这种不可见的行为漂移
 * 潜入登录路径（{@code BCrypt.checkpw} 按截断后的字节比对），是比 500 更坏的缺陷。
 */
public class Utf8ByteLengthValidator implements ConstraintValidator<Utf8ByteLength, CharSequence> {

    private int max;

    @Override
    public void initialize(Utf8ByteLength annotation) {
        this.max = annotation.max();
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return value.toString().getBytes(StandardCharsets.UTF_8).length <= max;
    }
}
