package com.travelagency.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelagency.common.enums.RouteStatus;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.RouteDetailResponse;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.Review;
import com.travelagency.domain.entity.RouteItineraryDay;
import com.travelagency.domain.entity.RouteItineraryItem;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.ReviewMapper;
import com.travelagency.domain.mapper.RouteItineraryDayMapper;
import com.travelagency.domain.mapper.RouteItineraryItemMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RouteService {

    private final TravelRouteMapper routeMapper;
    private final DepartureMapper departureMapper;
    private final RouteItineraryDayMapper dayMapper;
    private final RouteItineraryItemMapper itemMapper;
    private final ReviewMapper reviewMapper;

    public RouteService(
            TravelRouteMapper routeMapper,
            DepartureMapper departureMapper,
            RouteItineraryDayMapper dayMapper,
            RouteItineraryItemMapper itemMapper,
            ReviewMapper reviewMapper) {
        this.routeMapper = routeMapper;
        this.departureMapper = departureMapper;
        this.dayMapper = dayMapper;
        this.itemMapper = itemMapper;
        this.reviewMapper = reviewMapper;
    }

    public Page<TravelRoute> pagePublic(long current, long size, String keyword, String departureCity,
                                        String destination, BigDecimal minPrice, BigDecimal maxPrice,
                                        Integer durationDays, Integer departureMonth, boolean hasDeparture) {
        QueryWrapper<TravelRoute> wrapper = new QueryWrapper<>();
        wrapper.eq("status", RouteStatus.PUBLISHED).eq("deleted", 0);
        if (keyword != null && !keyword.isBlank()) {
            String value = keyword.trim();
            wrapper.and(w -> w.like("name", value)
                    .or().like("destination", value)
                    .or().like("departure_city", value)
                    .or().like("description", value)
                    .or().apply("EXISTS (SELECT 1 FROM route_itinerary_item ri "
                            + "JOIN route_itinerary_day rd ON rd.id = ri.day_id "
                            + "WHERE rd.route_id = travel_route.id AND ri.name LIKE {0})", "%" + value + "%"));
        }
        if (departureCity != null && !departureCity.isBlank()) {
            wrapper.eq("departure_city", departureCity.trim());
        }
        if (destination != null && !destination.isBlank()) {
            wrapper.like("destination", destination.trim());
        }
        if (durationDays != null) {
            wrapper.eq("duration_days", durationDays);
        }
        if (minPrice != null || maxPrice != null) {
            BigDecimal lower = minPrice == null ? BigDecimal.ZERO : minPrice;
            BigDecimal upper = maxPrice == null ? new BigDecimal("999999999") : maxPrice;
            wrapper.apply("EXISTS (SELECT 1 FROM departure d WHERE d.route_id = travel_route.id "
                    + "AND d.status = 'OPEN' AND d.adult_price BETWEEN {0} AND {1})", lower, upper);
        }
        if (departureMonth != null && departureMonth >= 1 && departureMonth <= 12) {
            wrapper.apply("EXISTS (SELECT 1 FROM departure d WHERE d.route_id = travel_route.id "
                    + "AND d.status = 'OPEN' AND MONTH(d.start_date) = {0})", departureMonth);
        }
        if (hasDeparture) {
            wrapper.apply("EXISTS (SELECT 1 FROM departure d WHERE d.route_id = travel_route.id "
                    + "AND d.status = 'OPEN' AND d.start_date >= CURRENT_DATE())");
        }
        wrapper.orderByDesc("created_at");
        Page<TravelRoute> result = routeMapper.selectPage(
                new Page<>(Math.max(current, 1), Math.min(Math.max(size, 1), 50)), wrapper);
        enrichStartingPrices(result);
        return result;
    }

    public Page<TravelRoute> pageAll(long current, long size, String keyword, String status) {
        QueryWrapper<TravelRoute> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted", 0);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like("name", keyword.trim()).or().like("destination", keyword.trim()));
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("created_at");
        return routeMapper.selectPage(new Page<>(Math.max(current, 1), Math.min(Math.max(size, 1), 100)), wrapper);
    }

    public RouteDetailResponse detail(Long routeId) {
        TravelRoute route = routeMapper.selectById(routeId);
        if (route == null || !RouteStatus.PUBLISHED.equals(route.status) || Integer.valueOf(1).equals(route.deleted)) {
            throw new BusinessException(404, "线路不存在或暂未上架");
        }
        return buildDetail(route);
    }

    private RouteDetailResponse buildDetail(TravelRoute route) {
        // 公开详情只展示仍可报名的团期，避免用户看到已过期或已关闭的团期。
        List<Departure> departures = departureMapper.selectList(new QueryWrapper<Departure>()
                .eq("route_id", route.id).eq("status", "OPEN").ge("start_date", java.time.LocalDate.now())
                .orderByAsc("start_date"));
        List<RouteDetailResponse.ItineraryDayView> itinerary = dayMapper.selectList(new QueryWrapper<RouteItineraryDay>()
                        .eq("route_id", route.id).orderByAsc("day_number"))
                .stream()
                .map(day -> new RouteDetailResponse.ItineraryDayView(day,
                        itemMapper.selectList(new QueryWrapper<RouteItineraryItem>()
                                .eq("day_id", day.id).orderByAsc("sort_no"))))
                .toList();
        List<Map<String, Object>> reviews = reviewMapper.selectMaps(new QueryWrapper<Review>()
                .eq("route_id", route.id).eq("status", "VISIBLE").orderByDesc("created_at"));
        return new RouteDetailResponse(route, departures, itinerary, reviews);
    }

    private void enrichStartingPrices(Page<TravelRoute> result) {
        if (result.getRecords().isEmpty()) {
            return;
        }
        List<Long> routeIds = result.getRecords().stream().map(route -> route.id).toList();
        Map<Long, BigDecimal> prices = new LinkedHashMap<>();
        departureMapper.selectMaps(new QueryWrapper<Departure>()
                        .select("route_id AS routeId", "MIN(adult_price) AS minAdultPrice")
                        .in("route_id", routeIds).eq("status", "OPEN").groupBy("route_id"))
                .forEach(row -> {
                    Object routeId = row.get("routeId");
                    Object price = row.get("minAdultPrice");
                    if (routeId != null && price != null) {
                        prices.put(Long.valueOf(routeId.toString()), new BigDecimal(price.toString()));
                    }
                });
        result.getRecords().forEach(route -> route.minAdultPrice = prices.get(route.id));
    }
}
