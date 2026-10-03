package com.travelagency.domain.dto;

import com.travelagency.common.enums.AccountStatus;
import com.travelagency.domain.entity.Hotel;
import com.travelagency.domain.entity.HotelImage;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 酒店视图，对齐契约 {@code Hotel}（{@code additionalProperties: false}，必填
 * {@code id/name/city/status/version/images/facilities/createdAt/updatedAt}）。
 *
 * <p>后台酒店管理（契约 {@code Admin Resources} 的 {@code /admin/hotels}）的所有端点共用这一份映射，
 * 保证列表、创建、修改三处的形状完全一致 —— 同一个资源在不同接口上给出不同口径，
 * 前端就得为每个端点各写一套解析。</p>
 *
 * <p>{@code version} 用 {@code Integer} 而不是 {@code Long}：契约里它是 JSON number，
 * 而全局序列化器会把 {@code Long} 写成字符串（那是给主键用的，避免 JS 精度丢失）。
 * 同理，坐标是 JSON number，因此统一转 {@code Double} —— 直出 {@code BigDecimal}
 * 会被全局序列化器变成两位小数字符串，既类型违约又把 7 位小数的坐标砍到 2 位。</p>
 *
 * <p><b>这是后台视图</b>：用户端看到的是 {@link HotelSummaryView}（行程摘要）与
 * {@link PublicHotelDetailView}（详情），两者都不含 {@code version} / {@code status} 等内部管理字段。</p>
 *
 * <p>{@code facilities} 在库内是 JSON 数组文本，这里按契约还原成字符串数组；
 * 未录入时给 {@code []} 而不是 {@code null}（契约声明的是数组）。</p>
 */
public record HotelView(
        Long id,
        String name,
        String city,
        String address,
        String contactPhone,
        String coverUrl,
        List<HotelImageView> images,
        Integer starRating,
        List<String> facilities,
        String checkInTime,
        String checkOutTime,
        Double longitude,
        Double latitude,
        String intro,
        String dataSource,
        String status,
        Integer version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /**
     * @param images     该酒店的详情图片，必须已按 {@code sortOrder} 升序排好（批量查询时由调用方排序）
     * @param facilities 已解析的设施标签；{@code null} 按空列表处理
     */
    public static HotelView from(Hotel hotel, List<HotelImage> images, List<String> facilities) {
        if (hotel == null) {
            return null;
        }
        return new HotelView(hotel.id, hotel.name, hotel.city, hotel.address, hotel.contactPhone,
                hotel.coverUrl,
                images == null ? List.of() : images.stream().map(HotelImageView::from).toList(),
                hotel.starRating,
                facilities == null ? List.of() : List.copyOf(facilities),
                hotel.checkInTime, hotel.checkOutTime,
                number(hotel.longitude), number(hotel.latitude), hotel.intro,
                hotel.dataSource, AccountStatus.of(hotel.status), hotel.version,
                hotel.createdAt, hotel.updatedAt);
    }

    /** 库内 1/0 → 契约 {@code AccountStatus}；再转 JSON number，绕开 BigDecimal 的两位小数序列化器。 */
    private static Double number(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
