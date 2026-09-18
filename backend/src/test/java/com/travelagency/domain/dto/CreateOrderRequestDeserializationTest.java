package com.travelagency.domain.dto;

import com.travelagency.common.config.JacksonConfig;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下单请求反序列化回归测试。
 *
 * <p>背景：全局 Jackson 配置开启了 {@code FAIL_ON_UNKNOWN_PROPERTIES}（见 {@link JacksonConfig}），
 * 一旦请求模型漏掉契约里定义的字段，调用方多传的那个字段就会从「被静默忽略」变成「硬报错 400」。</p>
 *
 * <p>真实事故：契约 {@code OrderTravelerRequest} 明确定义了 {@code sourceTravelerId}
 * （且 additionalProperties 为 false），前端 OrderCreateView 每次提交都会带上它（即使为 null 也会被序列化），
 * 但 {@code TravelerSnapshotRequest} 缺少该字段 —— 开启严格模式后前端下单直接 400，整条下单链路不可用。</p>
 *
 * <p>本测试不依赖 Spring 容器与数据库，直接按应用的真实 Jackson 配置构建 mapper，
 * 因此会随常规 {@code mvn test} 一起执行，防止该字段再次被移除。</p>
 */
class CreateOrderRequestDeserializationTest {

    private static final JsonMapper MAPPER = buildMapper();

    private static JsonMapper buildMapper() {
        JsonMapper.Builder builder = JsonMapper.builder();
        new JacksonConfig().contractJsonCustomizer().customize(builder);
        return builder.build();
    }

    private static String payload(String sourceTravelerIdJson) {
        return """
                {
                  "departureId": "1",
                  "adultCount": 1,
                  "childCount": 0,
                  "contactName": "张三",
                  "contactPhone": "13800001111",
                  "contactEmail": "zhangsan@example.test",
                  "remark": null,
                  "travelers": [
                    {
                      "travelerType": "ADULT",
                      %s
                      "name": "张三",
                      "gender": "MALE",
                      "birthDate": "1990-01-01",
                      "idType": "CHINESE_ID_CARD",
                      "idNo": "110101199001011234",
                      "phone": "13800001111",
                      "emergencyName": "李四",
                      "emergencyPhone": "13900001111"
                    }
                  ]
                }
                """.formatted(sourceTravelerIdJson);
    }

    @Test
    void acceptsNullSourceTravelerId() {
        CreateOrderRequest request = MAPPER.readValue(payload("\"sourceTravelerId\": null,"),
                CreateOrderRequest.class);
        assertEquals(1, request.travelers().size());
        assertNull(request.travelers().get(0).sourceTravelerId());
    }

    @Test
    void acceptsConcreteSourceTravelerId() {
        CreateOrderRequest request = MAPPER.readValue(payload("\"sourceTravelerId\": \"42\","),
                CreateOrderRequest.class);
        assertEquals(42L, request.travelers().get(0).sourceTravelerId());
    }

    /** 契约把 travelerType 列为 required，同时确认该字段仍被严格校验。 */
    @Test
    void keepsTravelerType() {
        CreateOrderRequest request = MAPPER.readValue(payload("\"sourceTravelerId\": null,"),
                CreateOrderRequest.class);
        assertEquals("ADULT", request.travelers().get(0).travelerType());
    }

    /** 严格模式本身仍然生效：契约外的字段必须继续被拒绝，避免这次修复把校验整体放开。 */
    @Test
    void stillRejectsFieldsOutsideTheContract() {
        boolean rejected;
        try {
            MAPPER.readValue(payload("\"sourceTravelerId\": null, \"notInContract\": 1,"),
                    CreateOrderRequest.class);
            rejected = false;
        } catch (RuntimeException expected) {
            rejected = true;
        }
        assertTrue(rejected, "契约外的字段应当继续被严格模式拒绝");
    }
}
