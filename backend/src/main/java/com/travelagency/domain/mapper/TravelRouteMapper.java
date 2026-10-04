package com.travelagency.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelagency.domain.dto.HomeView;
import com.travelagency.domain.entity.TravelRoute;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TravelRouteMapper extends BaseMapper<TravelRoute> {
    /**
     * 热门目的地：按<b>有效报名游客数量</b>统计（PRD §27），供 {@code GET /home} 与后台工作台共用。
     *
     * <p><b>"有效报名"与线路侧的 {@code travel_route.valid_booking_count} 是同一个集合</b>：
     * 该列在工作人员确认报名时 {@code +1}（{@code OrderService#confirm}）、退款走完流程时 {@code -1}
     * （{@code OrderService#processRefund}），因此集合 = 已确认（含出行中/已完成）且未完成退款的订单。
     * 热门线路按这个集合的<b>订单条数</b>排行（PRD：按有效报名订单统计），热门目的地按同一集合的
     * <b>游客人数</b>排行（PRD：按有效报名游客数量统计）——同一套"有效"，两种度量。</p>
     *
     * <p>不能像早先那样直接 {@code SUM(valid_booking_count)}：那一列是<b>订单条数</b>，
     * 于是"热门目的地"实际按订单数排行，与 PRD 的人数口径不符（1 单 6 人的目的地会排在
     * 2 单 4 人的目的地后面），而且目的地的定义会跟着线路计数一起漂移。</p>
     *
     * <p><b>为什么还要认 {@code REFUND_APPLYING}</b>：退款申请期间名额与计数都还没回退
     * （退款完成才 {@code -1}），这些订单仍属有效报名。但 {@code PAID_WAIT_CONFIRM} 的订单
     * <b>也能</b>申请退款（{@code OrderService#applyRefund} 允许"待确认"与"已确认"两种状态），
     * 而它从未被 {@code +1} 过 —— 光看订单状态分不出这两者，必须用退款单上的
     * {@code original_order_status}（申请前的业务状态）来区分。</p>
     */
    @Select("""
            SELECT r.destination AS destination,
                   CAST(COALESCE(SUM(o.adult_count + o.child_count), 0) AS SIGNED) AS validBookingCount
            FROM travel_route r
            JOIN travel_order o ON o.route_id = r.id
            WHERE r.status = 'PUBLISHED' AND r.deleted = 0
              AND (o.status IN ('CONFIRMED', 'TRAVELLING', 'COMPLETED')
                   OR EXISTS (SELECT 1 FROM refund f
                              WHERE f.order_id = o.id
                                AND f.status IN ('APPLYING', 'PROCESSING')
                                AND f.original_order_status IN ('CONFIRMED', 'TRAVELLING')))
            GROUP BY r.destination
            HAVING SUM(o.adult_count + o.child_count) > 0
            ORDER BY validBookingCount DESC, r.destination ASC
            LIMIT 8
            """)
    List<HomeView.Destination> popularDestinations();

    @Select("""
            SELECT r.*
            FROM travel_route r
            JOIN (
                SELECT route_id, MIN(start_date) AS next_start_date
                FROM departure
                WHERE status = #{departureStatus}
                  AND start_date >= CURRENT_DATE()
                  AND reserved_people + confirmed_people < max_people
                GROUP BY route_id
            ) next_departure ON next_departure.route_id = r.id
            WHERE r.status = #{routeStatus} AND r.deleted = 0
            ORDER BY next_departure.next_start_date ASC, r.id ASC
            LIMIT 8
            """)
    List<TravelRoute> selectUpcomingRoutes(@Param("routeStatus") String routeStatus,
                                           @Param("departureStatus") String departureStatus);
}
