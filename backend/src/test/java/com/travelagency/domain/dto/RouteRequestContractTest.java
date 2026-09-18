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
        assertTrue(VALIDATOR.validate(new ItineraryDayRequest(1, "上海 → 昆明", null, null, null, null)).isEmpty());
        assertTrue(hasViolation(new ItineraryDayRequest(0, "标题", null, null, null, null), "dayNumber"));
        assertTrue(hasViolation(new ItineraryDayRequest(1, " ", null, null, null, null), "title"));
        assertTrue(hasViolation(new ItineraryDayRequest(null, "标题", null, null, null, null), "dayNumber"));
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
    }

    private static boolean hasViolation(Object target, String property) {
        return VALIDATOR.validate(target).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals(property));
    }
}
