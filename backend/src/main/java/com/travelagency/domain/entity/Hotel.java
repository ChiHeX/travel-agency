package com.travelagency.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.travelagency.common.model.BaseEntity;

import java.math.BigDecimal;

@TableName("hotel")
public class Hotel extends BaseEntity {
    public String name;
    /** 展示城市，最多 64 字符；后台列表的筛选维度。存量数据由迁移脚本补成空串（表示尚未录入）。 */
    public String city;
    public String address;
    /** 内部对接人 / 前台电话，只用于后台；公开端点不返回该字段。 */
    public String contactPhone;
    /** 用户端酒店卡片封面；为 null 时前端回退到第一张 images 或占位图。 */
    public String coverUrl;
    public BigDecimal longitude;
    public BigDecimal latitude;
    /** 官方星级 1~5；没有可靠依据时为 null，不用网站评分或"几钻"代替。 */
    public Integer starRating;
    public String intro;
    /**
     * 设施标签：契约 {@code HotelFacility} 枚举组成的 JSON 数组文本（库内是 JSON 列）。
     * 未录入时为 null，接口按契约返回 {@code []}。
     */
    public String facilities;
    /** 通常的入住时刻，契约 {@code ClockTime} 的 {@code HH:mm} 文本。 */
    public String checkInTime;
    /** 通常的退房时刻，契约 {@code ClockTime} 的 {@code HH:mm} 文本。 */
    public String checkOutTime;
    public String dataSource;
    public Integer status;
    /** 乐观锁版本号：新建为 0，每次成功修改递增 1（见 HotelService#update）。 */
    public Integer version;
}
