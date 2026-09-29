package com.travelagency.domain.dto;

import com.travelagency.common.enums.AccountStatus;
import com.travelagency.domain.entity.Hotel;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 酒店视图，对齐契约 {@code Hotel}（{@code additionalProperties: false}，必填
 * {@code id/name/status/createdAt/updatedAt}）。
 *
 * <p>后台酒店管理（契约 {@code Admin Resources} 的 {@code /admin/hotels}）的所有端点共用这一份映射，
 * 保证列表、创建、修改三处的形状完全一致 —— 同一个资源在不同接口上给出不同口径，
 * 前端就得为每个端点各写一套解析。</p>
 *
 * <p><b>为什么必须有这一层，不能直接返回实体</b>（{@code AdminController} 早期实现的做法）：</p>
 * <ul>
 *   <li>{@code status}：实体里是 {@code TINYINT} 1/0，契约是 {@code AccountStatus} 枚举
 *       {@code ACTIVE/DISABLED}。直出实体时前端拿到的是整数 {@code 1}，
 *       判断 {@code row.status === 'ACTIVE'} 永远不成立，后台表格只能把 1 当成一个"看起来像成功"的标签；</li>
 *   <li>{@code longitude} / {@code latitude}：契约是 JSON number（{-180,180} / {-90,90}），
 *       而全局 {@link com.travelagency.common.config.JacksonConfig} 会把 {@code BigDecimal}
 *       序列化成<b>两位小数字符串</b>——直出实体时坐标变成 {@code "100.17"}，
 *       既类型违约又把 7 位小数的坐标精度砍到 2 位（每日行程按酒店坐标连线时会明显偏移）。这里统一转 {@code Double}。</li>
 * </ul>
 */
public record HotelView(
        Long id,
        String name,
        String address,
        String contactPhone,
        Double longitude,
        Double latitude,
        String intro,
        String dataSource,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static HotelView from(Hotel hotel) {
        if (hotel == null) {
            return null;
        }
        return new HotelView(hotel.id, hotel.name, hotel.address, hotel.contactPhone,
                number(hotel.longitude), number(hotel.latitude), hotel.intro,
                hotel.dataSource, AccountStatus.of(hotel.status),
                hotel.createdAt, hotel.updatedAt);
    }

    /** 库内 1/0 → 契约 {@code AccountStatus}；再转 JSON number，绕开 BigDecimal 的两位小数序列化器。 */
    private static Double number(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
