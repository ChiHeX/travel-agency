package com.travelagency.common.enums;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class HotelFacility {

    public static final String WIFI = "WIFI";
    public static final String PARKING = "PARKING";
    public static final String RESTAURANT = "RESTAURANT";
    // 酒店提供早餐服务，不代表线路含早餐。
    public static final String BREAKFAST_SERVICE = "BREAKFAST_SERVICE";
    public static final String AIR_CONDITIONING = "AIR_CONDITIONING";
    public static final String FRONT_DESK_24H = "FRONT_DESK_24H";
    public static final String LUGGAGE_STORAGE = "LUGGAGE_STORAGE";
    public static final String ELEVATOR = "ELEVATOR";
    public static final String LAUNDRY = "LAUNDRY";
    public static final String GYM = "GYM";
    public static final String SWIMMING_POOL = "SWIMMING_POOL";
    public static final String AIRPORT_SHUTTLE = "AIRPORT_SHUTTLE";
    public static final String NON_SMOKING_ROOM = "NON_SMOKING_ROOM";
    public static final String ACCESSIBLE_FACILITIES = "ACCESSIBLE_FACILITIES";

    public static final List<String> VALUES = List.of(
            WIFI, PARKING, RESTAURANT, BREAKFAST_SERVICE, AIR_CONDITIONING, FRONT_DESK_24H,
            LUGGAGE_STORAGE, ELEVATOR, LAUNDRY, GYM, SWIMMING_POOL, AIRPORT_SHUTTLE,
            NON_SMOKING_ROOM, ACCESSIBLE_FACILITIES);

    public static final String PATTERN =
            "WIFI|PARKING|RESTAURANT|BREAKFAST_SERVICE|AIR_CONDITIONING|FRONT_DESK_24H|LUGGAGE_STORAGE|ELEVATOR"
                    + "|LAUNDRY|GYM|SWIMMING_POOL|AIRPORT_SHUTTLE|NON_SMOKING_ROOM|ACCESSIBLE_FACILITIES";

    public static final String MESSAGE = "酒店设施只能是契约 HotelFacility 枚举中的取值";

    private static final Set<String> ALL = Set.copyOf(VALUES);

    private HotelFacility() {
    }

    public static boolean isValid(String value) {
        return value != null && ALL.contains(value);
    }

    public static List<String> normalize(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (!isValid(value)) {
                throw new IllegalArgumentException("酒店设施只能是契约 HotelFacility 枚举中的取值：" + value);
            }
            if (!unique.add(value)) {
                throw new IllegalArgumentException("酒店设施标签不能重复：" + value);
            }
        }
        return List.copyOf(unique);
    }
}
