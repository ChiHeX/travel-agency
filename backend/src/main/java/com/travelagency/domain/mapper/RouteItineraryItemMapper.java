package com.travelagency.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelagency.domain.entity.RouteItineraryItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RouteItineraryItemMapper extends BaseMapper<RouteItineraryItem> {
    @Select("SELECT DISTINCT rd.route_id FROM route_itinerary_item ri "
            + "JOIN route_itinerary_day rd ON rd.id = ri.day_id "
            + "JOIN travel_route r ON r.id = rd.route_id "
            + "WHERE ri.attraction_id = #{attractionId} "
            + "AND r.status = 'PUBLISHED' AND r.deleted = 0")
    List<Long> publishedRouteIdsForAttraction(@Param("attractionId") Long attractionId);
}
