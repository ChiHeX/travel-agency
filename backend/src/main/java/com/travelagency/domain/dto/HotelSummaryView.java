package com.travelagency.domain.dto;

import com.travelagency.common.enums.AccountStatus;
import com.travelagency.domain.entity.Hotel;

/**
 * 内嵌在每日行程里的酒店摘要，对齐契约 {@code HotelSummary}。
 *
 * <p>线路详情的每日行程只带这张卡片需要的字段（名称、城市、地址、封面、官方星级），
 * 图片、完整简介、设施与坐标按需调用
 * {@code GET /routes/{routeId}/hotels/{hotelId}} 获取 —— 一次线路详情请求可能包含多天、
 * 多家酒店，把每家酒店的图片与长简介都塞进来会让详情响应随图片数量线性变胖。</p>
 *
 * <p><b>已停用（{@code DISABLED}）的酒店返回 {@code null} 摘要</b>：行程里<em>保留</em>
 * {@code hotelId} / {@code hotelName}（历史事实不改写，后台也不会因为停用就去改动已上架线路），
 * 但公开页面的酒店详情只对启用中的酒店开放（同一家酒店在
 * {@code GET /routes/{routeId}/hotels/{hotelId}} 上会返回 404）。
 * 若这里照旧给出摘要，用户端就会出现一个点了必然报错的"酒店详情"入口。</p>
 *
 * <p>不含评分、评价数量或销量：本项目没有酒店评价体系，官方星级 {@code starRating} 与
 * 网站评分、"几钻"不是一回事。</p>
 */
public record HotelSummaryView(
        Long id,
        String name,
        String city,
        String address,
        String coverUrl,
        Integer starRating) {

    /**
     * @return 酒店不存在或已停用时返回 {@code null}（见类注释：不要让摘要指向打不开的详情页）
     */
    public static HotelSummaryView forItinerary(Hotel hotel) {
        if (hotel == null || !AccountStatus.ACTIVE.equals(AccountStatus.of(hotel.status))) {
            return null;
        }
        return new HotelSummaryView(hotel.id, hotel.name, hotel.city, hotel.address,
                hotel.coverUrl, hotel.starRating);
    }
}
