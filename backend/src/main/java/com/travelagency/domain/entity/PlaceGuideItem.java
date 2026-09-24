package com.travelagency.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("place_guide_item")
public class PlaceGuideItem {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long guideId;
    public Long attractionId;
    public Integer sortOrder;
    public String note;
}
