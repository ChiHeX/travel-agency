package com.travelagency.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
