package com.travelagency.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.travelagency.common.model.BaseEntity;

@TableName("hotel_image")
public class HotelImage extends BaseEntity {
    public Long hotelId;
    public String url;
    public String alt;
    public Integer sortOrder;
}
