package com.travelagency.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link CoordinatePairComplete} 的实现。
 *
 * <p>只判断"是否成对"，不判断取值范围（那是 {@code @DecimalMin} / {@code @DecimalMax} 的职责），
 * 也不做任何"补全"（不会自作主张把缺失的一边补成 0）：越界与半截坐标都应明确回 422，
 * 而不是留下一条看似有效、实则无法在地图上定位的记录。</p>
 *
 * <p>报错刻意<b>改挂到 {@code longitude} 字段上</b>（而不是默认的类级约束）：
 * 类级约束会被 Spring 归入 global errors，全局异常处理只读 {@code getFieldErrors()}，
 * 于是客户端拿到的是泛泛的"请求参数校验失败"，看不到"经纬度要成对"这条真正的提示。
 * 挂到字段上后，它与其它字段校验一样进 {@code errors[]}，前端可就地展示。</p>
 */
public class CoordinatePairValidator implements ConstraintValidator<CoordinatePairComplete, HasCoordinatePair> {

    @Override
    public boolean isValid(HasCoordinatePair value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        if ((value.longitude() == null) == (value.latitude() == null)) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode("longitude")
                .addConstraintViolation();
        return false;
    }
}
