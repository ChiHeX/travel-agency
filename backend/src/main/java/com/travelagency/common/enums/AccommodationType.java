package com.travelagency.common.enums;

import java.util.List;

/** 未关联酒店按 PENDING 处理；NONE 必须显式指定。 */
public final class AccommodationType {

    public static final String HOTEL = "HOTEL";

    public static final String STANDARD = "STANDARD";

    public static final String NONE = "NONE";

    public static final String PENDING = "PENDING";

    public static final List<String> VALUES = List.of(HOTEL, STANDARD, NONE, PENDING);

    public static final String PATTERN = "HOTEL|STANDARD|NONE|PENDING";

    public static final String MESSAGE = "住宿安排类型只能是 HOTEL、STANDARD、NONE 或 PENDING";

    private AccommodationType() {
    }

    public static boolean isValid(String value) {
        return value != null && VALUES.contains(value);
    }

    public static String of(String stored) {
        return isValid(stored) ? stored : PENDING;
    }

    public static String infer(Long hotelId) {
        return hotelId == null ? PENDING : HOTEL;
    }

    public static boolean allowsHotel(String type) {
        return HOTEL.equals(type);
    }

    public record Violation(String field, String message) {
    }

    public static Violation violation(String submittedType, Long hotelId, String accommodationStandard) {
        String type = isValid(submittedType) ? submittedType : infer(hotelId);
        if (HOTEL.equals(type) && hotelId == null) {
            return new Violation("hotelId", "住宿类型为 HOTEL 时必须指定酒店");
        }
        if (!allowsHotel(type) && hotelId != null) {
            return new Violation("hotelId", "住宿类型为 " + type + " 时不能关联酒店："
                    + "请把 hotelId 留空，需要指定酒店时把住宿类型改为 HOTEL");
        }
        if (STANDARD.equals(type)
                && (accommodationStandard == null || accommodationStandard.isBlank())) {
            return new Violation("accommodationStandard", "住宿类型为 STANDARD 时必须填写住宿标准");
        }
        return null;
    }
}
