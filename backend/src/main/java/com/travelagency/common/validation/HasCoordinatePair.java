package com.travelagency.common.validation;

/**
 * 暴露一对经纬度的请求/数据模型，供 {@link CoordinatePairComplete} 做跨字段校验。
 *
 * <p>请求 DTO 都是 record，组件 {@code longitude} / {@code latitude} 会自动生成同名访问器，
 * 因此实现本接口不需要额外代码（记录自动满足接口）。这样校验器无需按名字反射取值，
 * 也不会因为字段改名而静默失效。</p>
 */
public interface HasCoordinatePair {

    /** 经度，未录入为 {@code null}。 */
    Double longitude();

    /** 纬度，未录入为 {@code null}。 */
    Double latitude();
}
