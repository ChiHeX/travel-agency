package com.travelagency.common.validation;

/**
 * 提供住宿安排字段的请求（每日行程的新增 / 修改），供
 * {@link AccommodationConsistent} 跨字段校验使用。
 *
 * <p>与 {@link HasCoordinatePair} 同一套路：类级约束的实现需要读字段，
 * 但约束本身要能用在不同的请求类型上，因此抽一个只读接口。</p>
 */
public interface HasAccommodationArrangement {

    /** 契约 {@code AccommodationType}；{@code null} 表示本次请求没有提交该字段（按 hotelId 推断）。 */
    String accommodationType();

    /** 当天安排的酒店主键；{@code null} 表示没有关联酒店。 */
    Long hotelId();

    /** 住宿标准说明（{@code STANDARD} 必填）。 */
    String accommodationStandard();
}
