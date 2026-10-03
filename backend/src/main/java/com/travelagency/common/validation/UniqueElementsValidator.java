package com.travelagency.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link UniqueElements} 的实现。
 *
 * <p>报错信息里带上重复的那个取值（例如"酒店设施标签不能重复：WIFI"）：数组可能有十几项，
 * 只说"不能重复"等于让调用方自己去比对。信息用自定义模板写出，因此它既是字段级错误
 * （{@code errors[].field} 可定位），又保留了可直接展示的具体内容。</p>
 *
 * <p>{@code null} 元素不在这里判：元素级约束（如 {@code @Pattern} / {@code @NotNull}）
 * 各自负责，本约束只管"取值是否重复"。</p>
 */
public class UniqueElementsValidator implements ConstraintValidator<UniqueElements, List<?>> {

    @Override
    public boolean isValid(List<?> value, ConstraintValidatorContext context) {
        if (value == null || value.size() < 2) {
            return true;
        }
        Set<Object> seen = new HashSet<>();
        for (Object element : value) {
            if (element != null && !seen.add(element)) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate("取值不能重复：" + element)
                        .addConstraintViolation();
                return false;
            }
        }
        return true;
    }
}
