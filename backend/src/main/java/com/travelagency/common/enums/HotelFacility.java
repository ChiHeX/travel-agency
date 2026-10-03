package com.travelagency.common.enums;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 酒店设施标签，取值对齐契约 {@code HotelFacility} 枚举。
 *
 * <p>用固定枚举而不是自由文本：用户端酒店详情页按标签渲染图标与名称，
 * 自由文本（"免费WiFi"、"无线网"、"wifi"）会变成一堆需要人工归一的同义标签，
 * 而本次并不打算为设施做一套管理模块。</p>
 *
 * <p><b>{@link #BREAKFAST_SERVICE} 与当天行程的 {@code breakfastIncluded} 是两件事</b>：
 * 前者是"这家酒店有早餐服务"（酒店基础资料），后者是"本线路当天的住宿含早餐"
 * （当天行程安排）。两者不能互相推断 —— 酒店有早餐服务不等于本线路含早餐
 * （可能是不含早的房价），本线路含早餐也不代表酒店对外提供早餐服务。</p>
 */
public final class HotelFacility {

    public static final String WIFI = "WIFI";
    public static final String PARKING = "PARKING";
    public static final String RESTAURANT = "RESTAURANT";
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

    /** 契约枚举的取值集合，顺序与契约 {@code HotelFacility} 一致。 */
    public static final List<String> VALUES = List.of(
            WIFI, PARKING, RESTAURANT, BREAKFAST_SERVICE, AIR_CONDITIONING, FRONT_DESK_24H,
            LUGGAGE_STORAGE, ELEVATOR, LAUNDRY, GYM, SWIMMING_POOL, AIRPORT_SHUTTLE,
            NON_SMOKING_ROOM, ACCESSIBLE_FACILITIES);

    /**
     * {@code @Pattern} 用的取值白名单。注解属性必须是编译期常量，因此这里写成字面量，
     * 由 {@code HotelFacilityTest} 断言它与 {@link #VALUES} 保持一致（漏一个就会静默少一个可选值）。
     */
    public static final String PATTERN =
            "WIFI|PARKING|RESTAURANT|BREAKFAST_SERVICE|AIR_CONDITIONING|FRONT_DESK_24H|LUGGAGE_STORAGE|ELEVATOR"
                    + "|LAUNDRY|GYM|SWIMMING_POOL|AIRPORT_SHUTTLE|NON_SMOKING_ROOM|ACCESSIBLE_FACILITIES";

    /** 与 {@link #PATTERN} 配套的校验失败提示。 */
    public static final String MESSAGE = "酒店设施只能是契约 HotelFacility 枚举中的取值";

    private static final Set<String> ALL = Set.copyOf(VALUES);

    private HotelFacility() {
    }

    public static boolean isValid(String value) {
        return value != null && ALL.contains(value);
    }

    /**
     * 按提交顺序去重并校验，返回可安全落库的列表。
     *
     * <p>契约把 {@code facilities} 声明为 {@code uniqueItems: true}，重复标签属于违反契约的输入，
     * 因此这里抛 422 而不是静默去重：静默去重会让调用方以为"提交什么都成功了"，
     * 而重复项往往意味着前端拼错了数据。</p>
     *
     * @throws IllegalArgumentException 含非枚举取值或重复取值时
     */
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
