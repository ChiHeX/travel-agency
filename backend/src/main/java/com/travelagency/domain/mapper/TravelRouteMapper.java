package com.travelagency.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelagency.domain.dto.HomeView;
import com.travelagency.domain.entity.TravelRoute;
import org.apache.ibatis.annotations.Mapper;
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
}
