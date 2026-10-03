package com.travelagency.domain.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 地图坐标数据的一致性约束：经纬度必须成对提供（不依赖 Spring 容器与数据库）。
 *
 * <p>契约里 {@code AttractionUpsertRequest} / {@code HotelCreateRequest} /
 * {@code HotelUpdateRequest} / {@code ItineraryItemRequest} 都引用 {@code CoordinatePairRule}
 * （"提供了经度就必须提供纬度"，字段缺失与显式 {@code null} 等价），四个请求模型分别用
 * {@code @CoordinatePairComplete} 实现同一口径。这里的用例逐一把四档都钉住：</p>
 * <ul>
 *   <li>只给经度、或只给纬度 —— 拒绝（单点坐标在用户端地图上无法落点，会被静默丢弃）；</li>
 *   <li>两个都留空 —— 允许（表示未录入坐标；行程项此时整对继承所关联景点的坐标）；</li>
 *   <li>两个都给 —— 允许。</li>
 * </ul>
 *
 * <p>按约束文案断言，不依赖约束在 Hibernate Validator 里生成的属性路径，
 * 避免因为字段命名差异造成脆弱的测试。</p>
 */
class CoordinatePairValidationTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final String PAIR_MESSAGE = "经度和纬度需要同时填写，或同时留空";

    @Test
    @DisplayName("景点：经纬度只填一个被拒绝，成对或都留空放行")
    void attractionRequiresCompleteCoordinatePair() {
        assertHasPairViolation(attraction(100.1, null));
        assertHasPairViolation(attraction(null, 25.0));
        assertTrue(VALIDATOR.validate(attraction(100.1, 25.0)).isEmpty(), "成对的坐标应放行");
        assertTrue(VALIDATOR.validate(attraction(null, null)).isEmpty(), "两个都留空应放行");
    }

    @Test
    @DisplayName("酒店建档：经纬度只填一个被拒绝，成对或都留空放行")
    void hotelCreateRequiresCompleteCoordinatePair() {
        assertHasPairViolation(hotelCreate(102.8, null));
        assertHasPairViolation(hotelCreate(null, 24.8));
        assertTrue(VALIDATOR.validate(hotelCreate(102.8, 24.8)).isEmpty(), "成对的坐标应放行");
        assertTrue(VALIDATOR.validate(hotelCreate(null, null)).isEmpty(), "两个都留空应放行");
    }

    @Test
    @DisplayName("酒店修改：经纬度只填一个被拒绝，成对或都留空放行")
    void hotelUpdateRequiresCompleteCoordinatePair() {
        assertHasPairViolation(hotelUpdate(102.8, null));
        assertHasPairViolation(hotelUpdate(null, 24.8));
        assertTrue(VALIDATOR.validate(hotelUpdate(102.8, 24.8)).isEmpty(), "成对的坐标应放行");
        assertTrue(VALIDATOR.validate(hotelUpdate(null, null)).isEmpty(), "两个都留空应放行");
    }

    @Test
    @DisplayName("行程项目：经纬度只填一个被拒绝，都留空表示整对继承景点坐标")
    void itineraryItemRequiresCompleteCoordinatePair() {
        assertHasPairViolation(itineraryItem(100.1, null));
        assertHasPairViolation(itineraryItem(null, 25.0));
        assertTrue(VALIDATOR.validate(itineraryItem(100.1, 25.0)).isEmpty(), "成对的坐标应放行");
        assertTrue(VALIDATOR.validate(itineraryItem(null, null)).isEmpty(), "两个都留空应放行");
    }

    private static AttractionUpsertRequest attraction(Double longitude, Double latitude) {
        return new AttractionUpsertRequest("大理古城", "大理", "云南省大理市",
                longitude, latitude, "演示简介", "团队测试数据", "ACTIVE");
    }

    private static HotelCreateRequest hotelCreate(Double longitude, Double latitude) {
        return new HotelCreateRequest("演示酒店", "大理", "云南省大理市", "000-00000000",
                null, null, null, null, null, null,
                longitude, latitude, "演示简介", "团队测试数据", "ACTIVE");
    }

    private static HotelUpdateRequest hotelUpdate(Double longitude, Double latitude) {
        return new HotelUpdateRequest("演示酒店", "大理", "云南省大理市", "000-00000000",
                null, null, null, null, null, null,
                longitude, latitude, "演示简介", "团队测试数据", "ACTIVE", 0);
    }

    private static ItineraryItemRequest itineraryItem(Double longitude, Double latitude) {
        return new ItineraryItemRequest(1, "ATTRACTION", "大理古城", "游览古城", 5L,
                longitude, latitude);
    }

    /** 目标对象必须且仅因"经纬度不成对"产生一条校验错误。 */
    private static void assertHasPairViolation(Object target) {
        var violations = VALIDATOR.validate(target);
        assertFalse(violations.isEmpty(), "只填一个坐标应产生校验错误");
        assertTrue(violations.stream().anyMatch(v -> PAIR_MESSAGE.equals(v.getMessage())),
                "应给出成对填写的提示，实际：" + violations.stream().map(v -> v.getMessage()).toList());
    }
}
