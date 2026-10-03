package com.travelagency.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.travelagency.common.model.BaseEntity;

@TableName("route_itinerary_day")
public class RouteItineraryDay extends BaseEntity {
    public Long routeId;
    public Integer dayNumber;
    public String title;
    public String description;
    public String transportation;
    public String meals;
    public Long hotelId;
    /**
     * 当天的住宿安排类型（契约 {@code AccommodationType}：HOTEL / STANDARD / NONE / PENDING）。
     * 只有 {@code HOTEL} 才允许 {@code hotelId} 非空；库内默认 {@code PENDING}，
     * "缺少酒店关联"不等于"不含住宿"（见 {@code AccommodationType}）。
     */
    public String accommodationType;
    /** 住宿标准文字说明，{@code STANDARD} 必填；最多 500 字符。 */
    public String accommodationStandard;
    /** 本线路安排的房型；最多 100 字符。 */
    public String roomType;
    /** 本线路当天住宿是否含早餐：1 含 / 0 不含 / null 尚未说明。与酒店的早餐服务无关。 */
    public Integer breakfastIncluded;
    /** 拼房、入住说明等补充信息；最多 1000 字符。 */
    public String accommodationNote;
}
