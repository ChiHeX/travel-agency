package com.travelagency.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.travelagency.common.model.BaseEntity;
import java.time.LocalDateTime;

@TableName("place_guide")
public class PlaceGuide extends BaseEntity {
    public String title;
    public String summary;
    public String city;
    public String destination;
    public String coverUrl;
    public String status;
    public Long authorId;
    public LocalDateTime publishedAt;
}
