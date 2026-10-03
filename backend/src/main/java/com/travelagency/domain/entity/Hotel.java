package com.travelagency.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.travelagency.common.model.BaseEntity;

import java.math.BigDecimal;

@TableName("hotel")
public class Hotel extends BaseEntity {
    public String name;

    public String city;
    public String address;

    public String contactPhone;

    public String coverUrl;
    public BigDecimal longitude;
    public BigDecimal latitude;

    public Integer starRating;
    public String intro;

    public String facilities;

    public String checkInTime;

    public String checkOutTime;
    public String dataSource;
    public Integer status;
    /** 乐观锁版本号：新建为 0，每次成功修改递增 1（见 HotelService#update）。 */
    public Integer version;
}
