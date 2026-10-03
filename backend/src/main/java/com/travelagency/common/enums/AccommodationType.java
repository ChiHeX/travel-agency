package com.travelagency.common.enums;

import java.util.List;

/**
 * 每日行程的住宿安排类型，取值对齐契约 {@code AccommodationType} 枚举
 * {@code [HOTEL, STANDARD, NONE, PENDING]}。
 *
 * <p>为什么要单独一个字段：{@code route_itinerary_day.hotel_id} 是否为空过去被用来表达
 * "当天怎么安排住宿"，于是三种完全不同的情况挤在同一个 {@code null} 上 ——
 * 只确定了住宿标准（{@code STANDARD}）、还没确认（{@code PENDING}）、当天确实不含住宿
 * （{@code NONE}）。用户端只能把 {@code null} 显示成"住宿安排暂未提供"，
 * 而其中"不含住宿"是一种明确的行程事实，不该被含糊掉。</p>
 *
 * <p><b>缺少酒店关联不等于不含住宿</b>：{@link #infer(Long)} 因此在 {@code hotelId} 为空时
 * 一律给出 {@link #PENDING}，绝不会给出 {@link #NONE} —— {@code NONE} 只能由调用方明确提交。
 * 这也是存量数据的迁移口径：已有酒店的当天行程设为 {@code HOTEL}，未关联酒店的设为 {@code PENDING}。</p>
 */
public final class AccommodationType {

    /** 指定了具体酒店，{@code hotelId} 必须非空。 */
    public static final String HOTEL = "HOTEL";
    /** 只确定住宿标准，不指定酒店，{@code hotelId} 必须为空。 */
    public static final String STANDARD = "STANDARD";
    /** 当天不含住宿。 */
    public static final String NONE = "NONE";
    /** 住宿安排待确认。 */
    public static final String PENDING = "PENDING";

    /** 契约枚举的取值集合，顺序与契约一致（供校验信息与测试引用）。 */
    public static final List<String> VALUES = List.of(HOTEL, STANDARD, NONE, PENDING);

    /** {@code @Pattern} 用的取值白名单（注解属性必须是编译期常量）。 */
    public static final String PATTERN = "HOTEL|STANDARD|NONE|PENDING";

    /** 与 {@link #PATTERN} 配套的校验失败提示。 */
    public static final String MESSAGE = "住宿安排类型只能是 HOTEL、STANDARD、NONE 或 PENDING";

    private AccommodationType() {
    }

    public static boolean isValid(String value) {
        return value != null && VALUES.contains(value);
    }

    /**
     * 库内取值 → 契约取值。库内是 {@code VARCHAR(16) NOT NULL DEFAULT 'PENDING'}，
     * 只有手工改库才可能塞进白名单之外的值；这类值按 {@link #PENDING}（尚未确认）处理，
     * 不猜测成 {@code NONE}。
     */
    public static String of(String stored) {
        return isValid(stored) ? stored : PENDING;
    }

    /**
     * 未提交 {@code accommodationType} 时的推断口径：{@code hotelId} 非空 → {@link #HOTEL}，
     * 否则 → {@link #PENDING}。与存量数据迁移规则完全一致，**任何情况下都不会推断成 {@code NONE}**。
     */
    public static String infer(Long hotelId) {
        return hotelId == null ? PENDING : HOTEL;
    }

    /** 该类型是否允许携带 {@code hotelId}（只有 {@code HOTEL} 允许）。 */
    public static boolean allowsHotel(String type) {
        return HOTEL.equals(type);
    }

    /** 住宿安排的一处不自洽：{@code field} 是应挂到的字段名，{@code message} 可直接展示给调用方。 */
    public record Violation(String field, String message) {
    }

    /**
     * 住宿安排是否自洽（类型 / 酒店关联 / 住宿标准），对齐契约 {@code ItineraryDayRequest} 的住宿规则。
     *
     * <p><b>请求层校验与服务层共用这一份判定</b>：请求 DTO 上的 {@code @AccommodationConsistent}
     * 用它生成字段级 422；{@code AdminRouteService} 在写库前再用它兜一次底。两边各写一套规则，
     * 迟早会出现"校验放行、落库却是不一致的行"。服务层这一道不是多余的：
     * 直接构造请求对象调用 Service 的代码（导入、数据修复、后续的内部接口）绕不过它，
     * 而数据库层面拦不住 {@code accommodation_type='HOTEL'} 配 {@code hotel_id=NULL} 这种组合
     * （外键只管 {@code hotel_id} 指向的行是否存在）。</p>
     *
     * @param submittedType 提交的 {@code accommodationType}，未提交（{@code null} 或非法值）时按
     *                      {@link #infer(Long)} 推断
     * @return 第一条违规；全部合法时返回 {@code null}
     */
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
