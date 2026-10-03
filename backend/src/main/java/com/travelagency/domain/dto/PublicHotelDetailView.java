package com.travelagency.domain.dto;

import com.travelagency.domain.entity.Hotel;
import com.travelagency.domain.entity.HotelImage;

import java.math.BigDecimal;
import java.util.List;

/**
 * 用户端酒店详情，对齐契约 {@code PublicHotelDetail}
 * （{@code GET /routes/{routeId}/hotels/{hotelId}} 的 {@code data}）。
 *
 * <p><b>与后台 {@link HotelView} 分开，不是重复劳动</b>：后台那份要带 {@code version}、{@code status}、
 * {@code createdAt} / {@code updatedAt}，是给管理端做乐观锁与状态维护用的；这些字段对用户没有意义，
 * 公开出去还会顺带暴露后台维护节奏（什么时候建的、最近谁改过）。因此公开视图只挑用户需要的字段，
 * 并<b>不含任何联系方式</b>：{@code hotel.contact_phone} 在后台的口径是"内部对接人 / 前台电话"，
 * 当前没有"公开客服电话"的标记字段，也就无法确认某个号码属于酒店对外公开的客服电话，
 * 按最小暴露原则一律不外发（见 {@code docs/API.md} §10）。</p>
 *
 * <p><b>不含评分、评价数量或销量</b>：本项目没有酒店评价体系。{@code starRating} 只是官方星级，
 * 没有可靠依据时就是 {@code null}；不得用线路评分、"几钻"或订单量去填这个字段。</p>
 *
 * <p>{@code images} 与 {@code facilities} 按契约始终返回数组（无数据时 {@code []}），
 * 前端因此不需要区分"字段缺失"和"没有数据"两种情况。</p>
 */
public record PublicHotelDetailView(
        Long id,
        String name,
        String city,
        String address,
        String coverUrl,
        List<HotelImageView> images,
        Integer starRating,
        String intro,
        List<String> facilities,
        String checkInTime,
        String checkOutTime,
        Double longitude,
        Double latitude,
        String dataSource) {

    /**
     * @param images     必须已按 {@code sortOrder} 升序排好（由 {@code HotelService} 统一排序）
     * @param facilities 已解析的设施标签；{@code null} 按空列表处理
     */
    public static PublicHotelDetailView from(Hotel hotel, List<HotelImage> images, List<String> facilities) {
        if (hotel == null) {
            return null;
        }
        return new PublicHotelDetailView(hotel.id, hotel.name, hotel.city, hotel.address, hotel.coverUrl,
                images == null ? List.of() : images.stream().map(HotelImageView::from).toList(),
                hotel.starRating, hotel.intro,
                facilities == null ? List.of() : List.copyOf(facilities),
                hotel.checkInTime, hotel.checkOutTime,
                number(hotel.longitude), number(hotel.latitude), hotel.dataSource);
    }

    /** 契约里坐标是 JSON number；直出 {@code BigDecimal} 会被全局序列化器变成两位小数字符串。 */
    private static Double number(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
