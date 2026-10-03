package com.travelagency.domain.dto;

import com.travelagency.common.config.JacksonConfig;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 线路管理请求模型的契约一致性测试（不依赖 Spring 容器与数据库）。
 *
 * <p>覆盖三类容易被改坏的契约约束：
 * <ol>
 *   <li>请求模型必须声明契约里定义的全部字段，且不能多出契约外的字段
 *       （全局 Jackson 开启了 {@code FAIL_ON_UNKNOWN_PROPERTIES}，漏字段会让正常请求直接 400）；</li>
 *   <li>客户端不能提交 {@code status} 这类由服务端决定的字段；</li>
 *   <li>字段长度/取值范围的 Bean Validation 约束与契约中的 minLength / maxLength / minimum / maximum 一致。</li>
 * </ol></p>
 */
class RouteRequestContractTest {

    private static final JsonMapper MAPPER = buildMapper();
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static JsonMapper buildMapper() {
        JsonMapper.Builder builder = JsonMapper.builder();
        new JacksonConfig().contractJsonCustomizer().customize(builder);
        return builder.build();
    }

    private static String routePayload(String extraField) {
        return """
                {
                  "name": "测试线路",
                  "departureCity": "上海",
                  "destination": "云南",
                  "durationDays": 6,
                  "description": "线路简介",
                  "coverUrl": "https://example.test/cover.png",
                  "included": "交通与住宿",
                  "excluded": "个人消费",
                  "bookingNotice": "请携带身份证"
                  %s
                }
                """.formatted(extraField);
    }

    @Test
    void acceptsEveryContractFieldOfRouteUpsertRequest() {
        RouteUpsertRequest request = MAPPER.readValue(routePayload(""), RouteUpsertRequest.class);

        assertTrue(VALIDATOR.validate(request).isEmpty(), "契约内的合法请求不应产生校验错误");
        assertEquals("测试线路", request.name());
        assertEquals("上海", request.departureCity());
        assertEquals("云南", request.destination());
        assertEquals(6, request.durationDays());
        assertEquals("交通与住宿", request.included());
        assertEquals("个人消费", request.excluded());
        assertEquals("请携带身份证", request.bookingNotice());
    }

    /** 契约把 status 列为服务端字段（RouteUpsertRequest 里没有它），客户端提交必须被拒绝。 */
    @Test
    void rejectsServerOwnedStatusInjectedIntoRouteUpsertRequest() {
        assertThrows(RuntimeException.class,
                () -> MAPPER.readValue(routePayload(",\n  \"status\": \"PUBLISHED\""), RouteUpsertRequest.class));
    }

    @Test
    void rejectsUnknownFieldsOutsideTheContract() {
        assertThrows(RuntimeException.class,
                () -> MAPPER.readValue(routePayload(",\n  \"createdBy\": \"1\""), RouteUpsertRequest.class));
    }

    @Test
    void enforcesRouteUpsertRequestFieldBounds() {
        RouteUpsertRequest tooShortName = new RouteUpsertRequest(
                "短", "上海", "云南", 6, null, null, null, null, null);
        assertTrue(hasViolation(tooShortName, "name"));

        RouteUpsertRequest tooManyDays = new RouteUpsertRequest(
                "测试线路", "上海", "云南", 366, null, null, null, null, null);
        assertTrue(hasViolation(tooManyDays, "durationDays"));

        RouteUpsertRequest blankCity = new RouteUpsertRequest(
                "测试线路", "  ", "云南", 6, null, null, null, null, null);
        assertTrue(hasViolation(blankCity, "departureCity"));

        RouteUpsertRequest missingDays = new RouteUpsertRequest(
                "测试线路", "上海", "云南", null, null, null, null, null, null);
        assertTrue(hasViolation(missingDays, "durationDays"));
    }

    /** 上下架接口只接受 PUBLISHED / OFFLINE，DRAFT 不能通过该接口写入。 */
    @Test
    void routeStatusUpdateOnlyAcceptsPublishOrOffline() {
        assertTrue(VALIDATOR.validate(new RouteStatusUpdateRequest("PUBLISHED")).isEmpty());
        assertTrue(VALIDATOR.validate(new RouteStatusUpdateRequest("OFFLINE")).isEmpty());
        assertTrue(hasViolation(new RouteStatusUpdateRequest("DRAFT"), "status"));
        assertTrue(hasViolation(new RouteStatusUpdateRequest(null), "status"));
    }

    @Test
    void itineraryDayRequestRequiresDayNumberAndTitle() {
        assertTrue(VALIDATOR.validate(itineraryDay(1, "上海 → 昆明")).isEmpty());
        assertTrue(hasViolation(itineraryDay(0, "标题"), "dayNumber"));
        assertTrue(hasViolation(itineraryDay(1, " "), "title"));
        assertTrue(hasViolation(itineraryDay(null, "标题"), "dayNumber"));
    }

    @Test
    void itineraryDayRequestRejectsRouteRebinding() {
        assertThrows(RuntimeException.class, () -> MAPPER.readValue("""
                {"dayNumber": 1, "title": "Day 1", "routeId": "99"}
                """, ItineraryDayRequest.class));
    }

    @Test
    void itineraryItemRequestEnforcesTypeAndCoordinateRange() {
        assertTrue(VALIDATOR.validate(new ItineraryItemRequest(
                1, "ATTRACTION", "大理古城", "游览古城", 5L, 100.1, 25.2)).isEmpty());

        assertTrue(hasViolation(new ItineraryItemRequest(
                0, "ATTRACTION", "大理古城", null, null, null, null), "sortNo"));
        assertTrue(hasViolation(new ItineraryItemRequest(
                1, "SHOPPING", "大理古城", null, null, null, null), "itemType"));
        assertTrue(hasViolation(new ItineraryItemRequest(
                1, "ATTRACTION", "大理古城", null, null, 181.0, null), "longitude"));
        assertTrue(hasViolation(new ItineraryItemRequest(
                1, "ATTRACTION", "大理古城", null, null, null, -91.0), "latitude"));

        // 经纬度必须成对（契约 CoordinatePairRule）：只给一个会被 422 拒绝，
        // 因为单点坐标在用户端地图上无法落点，会被静默丢弃。按文案断言，不依赖约束的字段命名。
        assertTrue(hasMessage(new ItineraryItemRequest(
                1, "ATTRACTION", "大理古城", null, null, 100.1, null), "经度和纬度需要同时填写，或同时留空"));
        assertTrue(hasMessage(new ItineraryItemRequest(
                1, "ATTRACTION", "大理古城", null, null, null, 25.2), "经度和纬度需要同时填写，或同时留空"));
        // 两个都留空是合法的：此时行程项整对继承所关联景点的坐标（见 AdminRouteService）。
        assertTrue(VALIDATOR.validate(new ItineraryItemRequest(
                1, "ATTRACTION", "大理古城", null, null, null, null)).isEmpty());
    }

    /**
     * 住宿安排的自洽规则（契约 {@code ItineraryDayRequest} 的住宿规则）：
     * HOTEL 必须带酒店；STANDARD 必须写住宿标准且不带酒店；NONE / PENDING 不得带酒店。
     * 未提交类型时按 hotelId 推断，且绝不推断成 NONE（"没填酒店"不等于"不含住宿"）。
     */
    @Test
    void itineraryDayRequestKeepsAccommodationConsistentWithTheHotel() {
        // HOTEL + 酒店：合法
        assertTrue(VALIDATOR.validate(itineraryDay(1, "第一天", "HOTEL", null, 12L)).isEmpty());
        // 未提交类型 + 酒店：按 HOTEL 推断，合法
        assertTrue(VALIDATOR.validate(itineraryDay(1, "第一天", null, null, 12L)).isEmpty());
        // 未提交类型 + 无酒店：按 PENDING 推断，合法（不会被当成"不含住宿"）
        assertTrue(VALIDATOR.validate(itineraryDay(1, "第一天", null, null, null)).isEmpty());
        assertTrue(VALIDATOR.validate(itineraryDay(1, "第一天", "PENDING", null, null)).isEmpty());
        assertTrue(VALIDATOR.validate(itineraryDay(1, "第一天", "NONE", null, null)).isEmpty());
        // STANDARD 必须写住宿标准
        assertTrue(VALIDATOR.validate(itineraryDay(1, "第一天", "STANDARD", "市区舒适型酒店", null)).isEmpty());

        assertTrue(hasViolation(itineraryDay(1, "第一天", "HOTEL", null, null), "hotelId"),
                "HOTEL 没带酒店应被拒绝");
        assertTrue(hasViolation(itineraryDay(1, "第一天", "STANDARD", " ", null), "accommodationStandard"),
                "STANDARD 没写住宿标准应被拒绝");
        assertTrue(hasViolation(itineraryDay(1, "第一天", "NONE", null, 12L), "hotelId"),
                "NONE 不该关联酒店");
        assertTrue(hasViolation(itineraryDay(1, "第一天", "PENDING", null, 12L), "hotelId"),
                "PENDING 不该关联酒店");
        assertTrue(hasViolation(itineraryDay(1, "第一天", "STANDARD", "标准", 12L), "hotelId"),
                "STANDARD 不该关联酒店");
        // 枚举外的取值由 @Pattern 拒绝
        assertTrue(hasViolation(itineraryDay(1, "第一天", "CAMPING", null, null), "accommodationType"));
    }

    /** 住宿字段的长度上限与时间格式按契约校验（超长与非法格式都应是 422，不落库）。 */
    @Test
    void itineraryDayRequestEnforcesAccommodationTextRules() {
        assertTrue(hasViolation(new ItineraryDayRequest(1, "第一天", null, null, null, "STANDARD",
                "标".repeat(501), null, null, null, null), "accommodationStandard"));
        assertTrue(hasViolation(new ItineraryDayRequest(1, "第一天", null, null, null, null,
                null, "房".repeat(101), null, null, null), "roomType"));
        assertTrue(hasViolation(new ItineraryDayRequest(1, "第一天", null, null, null, null,
                null, null, null, "注".repeat(1001), null), "accommodationNote"));

        // 三态布尔：false 与未提交是两种不同结果，都必须能通过校验
        assertTrue(VALIDATOR.validate(new ItineraryDayRequest(1, "第一天", null, null, null, null,
                null, null, Boolean.FALSE, null, null)).isEmpty());
        assertTrue(VALIDATOR.validate(new ItineraryDayRequest(1, "第一天", null, null, null, null,
                null, null, Boolean.TRUE, null, null)).isEmpty());
        assertTrue(VALIDATOR.validate(new ItineraryDayRequest(1, "第一天", null, null, null, null,
                null, null, null, null, null)).isEmpty());
    }

    /** 每日行程的构造夹具：只关心住宿相关的几个字段时，其余字段保持为空。 */
    private static ItineraryDayRequest itineraryDay(Integer dayNumber, String title) {
        return itineraryDay(dayNumber, title, null, null, null);
    }

    private static ItineraryDayRequest itineraryDay(Integer dayNumber, String title,
                                                    String accommodationType, String standard, Long hotelId) {
        return new ItineraryDayRequest(dayNumber, title, null, null, null,
                accommodationType, standard, null, null, null, hotelId);
    }

    private static boolean hasViolation(Object target, String property) {
        return VALIDATOR.validate(target).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals(property));
    }

    /** 按约束文案判断，避免类级/方法级约束的字段命名差异影响断言。 */
    private static boolean hasMessage(Object target, String message) {
        return VALIDATOR.validate(target).stream()
                .anyMatch(violation -> message.equals(violation.getMessage()));
    }
}
