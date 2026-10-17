package com.travelagency.common.validation;

import com.travelagency.common.enums.AccommodationType;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** 错误挂到具体字段，否则全局异常处理无法返回字段提示。 */
public class AccommodationConsistentValidator
        implements ConstraintValidator<AccommodationConsistent, HasAccommodationArrangement> {

    @Override
    public boolean isValid(HasAccommodationArrangement value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        AccommodationType.Violation violation = AccommodationType.violation(
                value.accommodationType(), value.hotelId(), value.accommodationStandard());
        if (violation == null) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(violation.message())
                .addPropertyNode(violation.field())
                .addConstraintViolation();
        return false;
    }
}
