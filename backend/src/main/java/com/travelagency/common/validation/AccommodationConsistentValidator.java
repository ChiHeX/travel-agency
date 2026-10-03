package com.travelagency.common.validation;

import com.travelagency.common.enums.AccommodationType;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link AccommodationConsistent} 的实现：判定逻辑复用
 * {@link AccommodationType#violation}，与 {@code AdminRouteService} 写库前的兜底检查是同一份规则。
 *
 * <p>报错改挂到具体字段上（{@code hotelId} / {@code accommodationStandard}）而不是留在类级约束上：
 * 类级约束会被 Spring 归入 global errors，而全局异常处理只读 {@code getFieldErrors()}，
 * 客户端拿到的就会是泛泛的"请求参数校验失败"，看不到"住宿类型与酒店关联不一致"这条真正的提示
 * （与 {@link CoordinatePairValidator} 同一理由）。</p>
 *
 * <p>一次只报第一条不满足的规则：同一份请求往往同时违反多条（例如 {@code STANDARD} 却带了酒店、
 * 又没写住宿标准），逐条堆在 {@code errors[]} 里不如给出"先改这一条"的明确指引。</p>
 */
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
