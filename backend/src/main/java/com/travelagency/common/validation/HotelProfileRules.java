package com.travelagency.common.validation;

/**
 * 酒店基础资料里几个跨模块共用的字段规则（契约 {@code ImageUrl} / {@code ClockTime} 与
 * {@code images} 的 {@code maxItems}）。集中一处，避免 DTO 注解、库内列宽与契约文档各写一份。
 *
 * <p>按 Unicode 码点计数（{@code @CodePointLength}），与 JSON Schema 的 {@code maxLength} 一致；
 * 库内列宽同样是字符口径（{@code VARCHAR(500)} 在 utf8mb4 下就是 500 个字符）。</p>
 */
public final class HotelProfileRules {

    // ------------------------------------------------------------------
    // 图片
    // ------------------------------------------------------------------

    /**
     * 图片地址上限（契约 {@code ImageUrl} 的 {@code maxLength: 500}，与 {@code hotel_image.url} /
     * {@code hotel.cover_url} 的列宽一致）。
     */
    public static final int IMAGE_URL_MAX_CHARS = 500;

    public static final String IMAGE_URL_LENGTH_MESSAGE = "图片地址不能超过 500 个字符";

    /**
     * 图片地址只接受 http / https 绝对地址（契约 {@code ImageUrl} 的 {@code pattern}）。
     *
     * <p>拦的是 {@code javascript:}、{@code data:}、相对路径与空串这类值：它们要么会被浏览器
     * 当成脚本执行、要么在其它域名下必然 404，而图片最终是渲染在<b>无需登录</b>的用户端页面上的。
     * 地址长度另由 {@link #IMAGE_URL_MAX_CHARS} 约束。</p>
     */
    public static final String IMAGE_URL_PATTERN = "https?://\\S+";

    public static final String IMAGE_URL_MESSAGE = "图片地址必须是 http 或 https 开头的完整地址";

    /** 单家酒店最多登记 10 张详情图片（契约 {@code images.maxItems}）。 */
    public static final int MAX_IMAGES = 10;

    public static final String IMAGE_COUNT_MESSAGE = "酒店图片最多 10 张";

    /** 图片替代文本上限（契约 {@code alt.maxLength}）。 */
    public static final int IMAGE_ALT_MAX_CHARS = 200;

    public static final String IMAGE_ALT_LENGTH_MESSAGE = "图片说明不能超过 200 个字符";

    // ------------------------------------------------------------------
    // 入住 / 退房时刻
    // ------------------------------------------------------------------

    /**
     * 24 小时制 {@code HH:mm}（契约 {@code ClockTime}）。
     *
     * <p>不接受 {@code 24:00}、{@code 9:00}、带秒或带时区的写法：它是"通常几点可以入住"的说明文字，
     * 不是时间戳，因此不做时区换算，也不允许能表示成多个不同含义的写法。</p>
     */
    public static final String CLOCK_TIME_PATTERN = "([01][0-9]|2[0-3]):[0-5][0-9]";

    public static final String CHECK_IN_TIME_MESSAGE = "入住时间必须使用 24 小时制 HH:mm 格式，例如 14:00";

    public static final String CHECK_OUT_TIME_MESSAGE = "退房时间必须使用 24 小时制 HH:mm 格式，例如 12:00";

    // ------------------------------------------------------------------
    // 文本长度
    // ------------------------------------------------------------------

    public static final int CITY_MAX_CHARS = 64;

    public static final String CITY_LENGTH_MESSAGE = "酒店城市不能超过 64 个字符";

    public static final int ACCOMMODATION_STANDARD_MAX_CHARS = 500;

    public static final String ACCOMMODATION_STANDARD_LENGTH_MESSAGE = "住宿标准不能超过 500 个字符";

    public static final int ROOM_TYPE_MAX_CHARS = 100;

    public static final String ROOM_TYPE_LENGTH_MESSAGE = "房型不能超过 100 个字符";

    public static final int ACCOMMODATION_NOTE_MAX_CHARS = 1000;

    public static final String ACCOMMODATION_NOTE_LENGTH_MESSAGE = "住宿补充说明不能超过 1000 个字符";

    private HotelProfileRules() {
    }
}
