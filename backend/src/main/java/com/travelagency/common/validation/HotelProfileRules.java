package com.travelagency.common.validation;

public final class HotelProfileRules {

    public static final int IMAGE_URL_MAX_CHARS = 500;

    public static final String IMAGE_URL_LENGTH_MESSAGE = "图片地址不能超过 500 个字符";

    public static final String IMAGE_URL_PATTERN = "https?://\\S+";

    public static final String IMAGE_URL_MESSAGE = "图片地址必须是 http 或 https 开头的完整地址";

    public static final int MAX_IMAGES = 10;

    public static final String IMAGE_COUNT_MESSAGE = "酒店图片最多 10 张";

    public static final int IMAGE_ALT_MAX_CHARS = 200;

    public static final String IMAGE_ALT_LENGTH_MESSAGE = "图片说明不能超过 200 个字符";

    public static final String CLOCK_TIME_PATTERN = "([01][0-9]|2[0-3]):[0-5][0-9]";

    public static final String CHECK_IN_TIME_MESSAGE = "入住时间必须使用 24 小时制 HH:mm 格式，例如 14:00";

    public static final String CHECK_OUT_TIME_MESSAGE = "退房时间必须使用 24 小时制 HH:mm 格式，例如 12:00";

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
