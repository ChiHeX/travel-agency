package com.travelagency.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.travelagency.common.model.BaseEntity;

/**
 * 酒店详情图片，对应 {@code hotel_image} 表。
 *
 * <p>只登记外部图片 URL：第一版由后台填写图片地址，项目不提供图片上传服务，
 * 因此删除图片记录只删除关联，<b>不会去删除外部图片文件</b>。</p>
 *
 * <p>顺序由 {@code sortOrder} 决定（升序），同序时按主键（即插入顺序）兜底，
 * 保证同一份数据每次返回的顺序都一样。</p>
 */
@TableName("hotel_image")
public class HotelImage extends BaseEntity {
    public Long hotelId;
    public String url;
    public String alt;
    public Integer sortOrder;
}
