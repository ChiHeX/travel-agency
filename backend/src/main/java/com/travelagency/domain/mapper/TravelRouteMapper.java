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
    @Select("""
            SELECT destination, SUM(valid_booking_count) AS validBookingCount
            FROM travel_route
            WHERE status = 'PUBLISHED' AND deleted = 0 AND valid_booking_count > 0
            GROUP BY destination
            ORDER BY validBookingCount DESC, destination ASC LIMIT 8
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
