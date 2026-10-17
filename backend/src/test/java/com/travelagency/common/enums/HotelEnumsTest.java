package com.travelagency.common.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotelEnumsTest {

    @Test
    @DisplayName("HotelFacility.PATTERN 与 VALUES 完全一致，且拒绝枚举外的取值")
    void hotelFacilityPatternMatchesTheDeclaredValues() {
        List<String> fromPattern = Arrays.asList(HotelFacility.PATTERN.split("\\|"));
        assertEquals(HotelFacility.VALUES, fromPattern,
                "PATTERN 必须与 VALUES 一一对应：漏一个就会让契约允许的设施被 422 拒绝");

        Pattern pattern = Pattern.compile(HotelFacility.PATTERN);
        for (String value : HotelFacility.VALUES) {
            assertTrue(pattern.matcher(value).matches(), "枚举取值必须匹配白名单：" + value);
            assertTrue(HotelFacility.isValid(value));
        }
        for (String rejected : List.of("WIFI_FREE", "wifi", "", "免费WiFi", "POOL")) {
            assertFalse(HotelFacility.isValid(rejected), "枚举外的取值应被拒绝：" + rejected);
            assertFalse(pattern.matcher(rejected).matches(), "枚举外的取值不该匹配白名单：" + rejected);
        }
    }

    @Test
    @DisplayName("设施标签：按提交顺序保留，重复或枚举外的取值直接报错")
    void normalizeKeepsOrderAndRejectsDuplicates() {
        assertEquals(List.of("PARKING", "WIFI"),
                HotelFacility.normalize(List.of("PARKING", "WIFI")), "顺序按提交顺序保留");
        assertEquals(List.of(), HotelFacility.normalize(null));
        assertEquals(List.of(), HotelFacility.normalize(List.of()));

        assertThrows(IllegalArgumentException.class,
                () -> HotelFacility.normalize(List.of("WIFI", "WIFI")));
        assertThrows(IllegalArgumentException.class,
                () -> HotelFacility.normalize(List.of("FREE_WIFI")));
    }

    @Test
    @DisplayName("AccommodationType：白名单一致，非法/空值按 PENDING 处理，推断绝不给出 NONE")
    void accommodationTypeNeverInfersNoneFromAMissingHotel() {
        List<String> fromPattern = Arrays.asList(AccommodationType.PATTERN.split("\\|"));
        assertEquals(AccommodationType.VALUES, fromPattern,
                "PATTERN 必须与 VALUES 一一对应：" + AccommodationType.VALUES);

        assertEquals(AccommodationType.HOTEL, AccommodationType.infer(12L),
                "给了酒店就按 HOTEL 处理");
        assertEquals(AccommodationType.PENDING, AccommodationType.infer(null),
                "缺少酒店关联必须落到 PENDING —— 不能解释成「当天不含住宿」");

        assertEquals(AccommodationType.PENDING, AccommodationType.of(null));
        assertEquals(AccommodationType.PENDING, AccommodationType.of("CAMPING"),
                "库内非白名单取值按待确认处理，不猜测成 NONE");
        assertEquals(AccommodationType.NONE, AccommodationType.of("NONE"),
                "人工明确写入的 NONE 必须原样保留");

        assertTrue(AccommodationType.allowsHotel(AccommodationType.HOTEL));
        for (String type : List.of(AccommodationType.STANDARD, AccommodationType.NONE, AccommodationType.PENDING)) {
            assertFalse(AccommodationType.allowsHotel(type), "只有 HOTEL 允许关联酒店：" + type);
        }
    }

    @Test
    @DisplayName("住宿自洽规则：类型与酒店关联/住宿标准的四种组合都在这一处判定")
    void accommodationViolationIsTheSingleSourceOfTruth() {
        assertNull(AccommodationType.violation("HOTEL", 12L, null), "HOTEL + 酒店：合法");
        assertNull(AccommodationType.violation(null, 12L, null), "未提交类型 + 酒店：按 HOTEL 推断，合法");
        assertNull(AccommodationType.violation(null, null, null), "未提交类型 + 无酒店：按 PENDING 推断，合法");
        assertNull(AccommodationType.violation("NONE", null, null), "NONE + 无酒店：合法");
        assertNull(AccommodationType.violation("PENDING", null, null), "PENDING + 无酒店：合法");
        assertNull(AccommodationType.violation("STANDARD", null, "市区舒适型酒店"), "STANDARD + 标准：合法");

        assertEquals("hotelId", AccommodationType.violation("HOTEL", null, null).field(),
                "HOTEL 没带酒店应挂在 hotelId 上");
        assertEquals("hotelId", AccommodationType.violation("NONE", 12L, null).field());
        assertEquals("hotelId", AccommodationType.violation("PENDING", 12L, null).field());
        assertEquals("hotelId", AccommodationType.violation("STANDARD", 12L, "标准").field(),
                "STANDARD 不该关联酒店");
        assertEquals("accommodationStandard", AccommodationType.violation("STANDARD", null, "  ").field(),
                "STANDARD 的住宿标准不能是空白");
        assertEquals("accommodationStandard", AccommodationType.violation("STANDARD", null, null).field());
    }
}
