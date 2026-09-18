package com.travelagency.domain.dto;

import com.travelagency.common.config.JacksonConfig;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.TravelRoute;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单详情响应形状回归测试，对齐契约 {@code OrderDetail}（components.schemas.OrderDetail，
 * route 为 RouteSummary、departure 为 Departure，两者 additionalProperties 均为 false）。
 *
 * <p>背景：{@code GET /orders/{orderNo}} 与 {@code GET /admin/orders/{orderNo}} 早期直接
 * 序列化持久化实体，导致两个方向同时出错：</p>
 * <ul>
 *   <li>多输出 —— TravelRoute 带出 included / excluded / bookingNotice / createdBy / deleted，
 *       Departure 带出 version（optimistic lock 内部字段）；</li>
 *   <li>少输出 —— Departure 缺少契约必填的 availableSeats 计算字段与 routeName / guideName。</li>
 * </ul>
 *
 * <p>本测试不依赖 Spring 容器与数据库，按应用真实 Jackson 配置直接序列化，
 * 因此会随常规 {@code mvn test} 一起执行，防止实体再次被直接暴露到该端点。</p>
 */
class OrderDetailContractShapeTest {

    private static final JsonMapper MAPPER = buildMapper();

    private static JsonMapper buildMapper() {
        JsonMapper.Builder builder = JsonMapper.builder();
        new JacksonConfig().contractJsonCustomizer().customize(builder);
        return builder.build();
    }

    private static TravelRoute route() {
        TravelRoute route = new TravelRoute();
        route.id = 7L;
        route.name = "昆明·大理·丽江 6 日跟团游";
        route.departureCity = "上海";
        route.destination = "云南";
        route.durationDays = 6;
        route.description = "深度体验云南经典自然与人文线路。";
        route.coverUrl = "https://images.unsplash.com/photo-1";
        route.included = "行程内交通、酒店及约定餐食。";
        route.excluded = "个人消费。";
        route.bookingNotice = "儿童价按身高计算。";
        route.status = "PUBLISHED";
        route.ratingAvg = new BigDecimal("4.80");
        route.ratingCount = 26;
        route.validBookingCount = 132;
        route.createdBy = 99L;
        route.deleted = 0;
        route.minAdultPrice = null;
        route.createdAt = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        route.updatedAt = LocalDateTime.of(2026, 1, 3, 3, 4, 5);
        return route;
    }

    private static Departure departure() {
        Departure departure = new Departure();
        departure.id = 11L;
        departure.routeId = 7L;
        departure.startDate = LocalDate.of(2026, 5, 1);
        departure.endDate = LocalDate.of(2026, 5, 6);
        departure.adultPrice = new BigDecimal("3999.00");
        departure.childPrice = new BigDecimal("1999.00");
        departure.maxPeople = 30;
        departure.reservedPeople = 4;
        departure.confirmedPeople = 8;
        departure.guideId = 5L;
        departure.status = "PUBLISHED";
        departure.version = 3;
        departure.createdAt = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        departure.updatedAt = LocalDateTime.of(2026, 1, 3, 3, 4, 5);
        return departure;
    }

    private static JsonNode detailJson() {
        TravelRoute route = route();
        Departure departure = departure();
        OrderDetailResponse response = new OrderDetailResponse(
                null,
                RouteSummaryView.from(route),
                DepartureView.from(departure, route.name, "李导"),
                List.of(),
                null,
                List.of(),
                null);
        return MAPPER.readTree(MAPPER.writeValueAsString(response));
    }

    /** 契约把 availableSeats 列为 required，且它是实体里不存在的计算字段。 */
    @Test
    void exposesRequiredAvailableSeats() {
        JsonNode departure = detailJson().get("departure");
        assertEquals(18, departure.get("availableSeats").asInt());
    }

    /** version 是乐观锁内部字段，契约 additionalProperties: false 不允许出现。 */
    @Test
    void neverLeaksDepartureVersion() {
        assertFalse(detailJson().get("departure").has("version"), "团期实体字段 version 不应出现在订单详情里");
    }

    /** 线路摘要只允许 RouteSummary 的字段，实体扩展字段必须被剥离。 */
    @Test
    void routeStaysWithinRouteSummarySchema() {
        JsonNode route = detailJson().get("route");
        for (String leaked : List.of("included", "excluded", "bookingNotice", "createdBy", "deleted")) {
            assertFalse(route.has(leaked), "线路实体字段 " + leaked + " 不应出现在订单详情里");
        }
        assertEquals("昆明·大理·丽江 6 日跟团游", route.get("name").asString());
    }

    /** 路由/团期的 Long 主键统一序列化为字符串，避免前端 JS 精度丢失。 */
    @Test
    void keepsIdSerializedAsString() {
        JsonNode route = detailJson().get("route");
        assertTrue(route.get("id").isString());
        assertEquals("7", route.get("id").asString());
    }

    /** ratingAvg 必须满足契约的 ^[0-5]\.[0-9]{2}$ 两位小数字符串。 */
    @Test
    void ratingAvgKeepsTwoDecimals() {
        assertEquals("4.80", detailJson().get("route").get("ratingAvg").asString());
    }

    /** 契约 required 的计数字段在实体为 null 时按 0 兜底，不能输出 null。 */
    @Test
    void ratingFieldsFallBackToZeroInsteadOfNull() {
        TravelRoute bare = new TravelRoute();
        bare.id = 8L;
        bare.name = "空线路";
        JsonNode route = MAPPER.readTree(MAPPER.writeValueAsString(RouteSummaryView.from(bare)));
        assertEquals("0.00", route.get("ratingAvg").asString());
        assertEquals(0, route.get("ratingCount").asInt());
        assertEquals(0, route.get("validBookingCount").asInt());
    }
}
