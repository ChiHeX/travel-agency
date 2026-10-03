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

    public String accommodationType;

    public String accommodationStandard;

    public String roomType;

    public Integer breakfastIncluded;

    public String accommodationNote;
}
