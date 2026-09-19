package com.travelagency.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.enums.RouteStatus;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.DepartureView;
import com.travelagency.domain.dto.RouteDetailView;
import com.travelagency.domain.dto.RouteSummaryView;
import com.travelagency.domain.dto.RouteView;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.Favorite;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.FavoriteMapper;
import com.travelagency.domain.mapper.GuideMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 线路公开浏览业务服务，实现契约 Routes 一组端点（{@code GET /routes}、
 * {@code GET /routes/{routeId}}）。
 *
 * <p>所有对外结果都组装成契约视图（{@link RouteSummaryView} / {@link RouteView} /
 * {@link DepartureView} / {@link ItineraryDayView} / {@link ReviewView}），
 * <b>不直接序列化实体</b>。实体直出会同时造成两类契约偏差：多出契约未声明的
 * {@code createdBy} / {@code deleted} / {@code version} 等内部字段（违反
 * {@code additionalProperties: false}），又缺少 {@code availableSeats} /
 * {@code nextDepartureDate} / {@code favorite} 这些需要联查或计算才能得到的字段。</p>
 *
 * <p>{@code availableSeats} 尤其关键：它是前端判断"该团期能否报名"的唯一依据，
 * 缺失会让详情页与报名页把所有团期当成不可报名。</p>
 */
@Service
public class RouteService {

    /**
     * 契约 {@code GET /routes} 声明的 sort 取值。
     *
     * <p>枚举之外的输入按 400 拒绝而不是静默忽略：契约在该路径声明了 400 响应，
     * 而 {@code FAIL_ON_UNKNOWN_PROPERTIES} 只作用于请求体，query 参数写错
     * 不会报错、会被直接丢弃，表现为"排序点了没反应"。</p>
     */
    private static final List<String> PUBLIC_SORTS = List.of(
            "minAdultPrice,asc", "minAdultPrice,desc", "validBookingCount,desc", "ratingAvg,desc");

    /**
     * 在售（OPEN）团期的最低价子查询，供 minAdultPrice 排序使用。
     *
     * <p>{@code minAdultPrice} 是团期聚合值而不是线路表的列，无法按列排序；
     * 这里把它作为排序别名挂在查询上，由数据库完成排序，避免"先分页再在内存排序"
     * 导致跨页顺序错误。</p>
     */
    private static final String MIN_ADULT_PRICE_SQL =
            "(SELECT MIN(d.adult_price) FROM departure d "
                    + "WHERE d.route_id = travel_route.id AND d.status = 'OPEN')";

    private static final long MAX_PAGE_SIZE = 50;

    private final TravelRouteMapper routeMapper;
    private final DepartureMapper departureMapper;
    private final GuideMapper guideMapper;
    private final FavoriteMapper favoriteMapper;
    private final AdminRouteService adminRouteService;
    private final OrderService orderService;

    public RouteService(
            TravelRouteMapper routeMapper,
            DepartureMapper departureMapper,
            GuideMapper guideMapper,
            FavoriteMapper favoriteMapper,
            AdminRouteService adminRouteService,
            OrderService orderService) {
        this.routeMapper = routeMapper;
        this.departureMapper = departureMapper;
        this.guideMapper = guideMapper;
        this.favoriteMapper = favoriteMapper;
        this.adminRouteService = adminRouteService;
        this.orderService = orderService;
    }

    /**
     * 公开线路分页查询，对齐契约 {@code GET /routes}。
     *
     * @param sort 契约声明的排序枚举；为 null 时按创建时间倒序
     * @param currentUserId 当前登录用户，未登录传 null（仅影响 favorite 字段）
     */
    public PageResponse<RouteSummaryView> pagePublic(long current, long size, String keyword, String departureCity,
                                                     String destination, BigDecimal minPrice, BigDecimal maxPrice,
                                                     Integer durationDays, Integer departureMonth,
                                                     boolean hasDeparture, String sort, Long currentUserId) {
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
        applyPublicSort(wrapper, sort);
        Page<TravelRoute> result = routeMapper.selectPage(
                new Page<>(Math.max(current, 1), Math.min(Math.max(size, 1), MAX_PAGE_SIZE)), wrapper);
        return toSummaryPage(result, currentUserId);
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

    /**
     * 我的收藏线路分页查询，对齐契约 {@code GET /favorites}（RoutePage）。
     *
     * <p>契约把该路径的响应定义为分页信封 + {@code RouteSummary}，前端也按
     * {@code data.items} 消费；返回裸数组会让收藏页永远显示为空。</p>
     */
    public PageResponse<RouteSummaryView> pageFavorites(Long userId, long current, long size) {
        Page<Favorite> favorites = favoriteMapper.selectPage(
                new Page<>(Math.max(current, 1), Math.min(Math.max(size, 1), MAX_PAGE_SIZE)),
                new QueryWrapper<Favorite>().eq("user_id", userId).orderByDesc("created_at"));
        List<Long> routeIds = favorites.getRecords().stream().map(f -> f.routeId).toList();
        // 收藏后线路被删除的情况要过滤掉，否则详情页会出现打不开的条目。
        Map<Long, TravelRoute> routes = new LinkedHashMap<>();
        if (!routeIds.isEmpty()) {
            routeMapper.selectByIds(routeIds).stream()
                    .filter(route -> route != null && Integer.valueOf(0).equals(route.deleted))
                    .forEach(route -> routes.put(route.id, route));
        }
        List<TravelRoute> ordered = routeIds.stream().map(routes::get).filter(java.util.Objects::nonNull).toList();
        Map<Long, BigDecimal> minPrices = minAdultPriceMap(routeIds);
        Map<Long, Departure> nextDepartures = nextOpenDepartureMap(routeIds);
        List<RouteSummaryView> items = ordered.stream()
                .map(route -> summaryOf(route, minPrices, nextDepartures, true))
                .toList();
        return new PageResponse<>(items, (int) favorites.getCurrent(), (int) favorites.getSize(),
                (int) favorites.getTotal(), (int) favorites.getPages());
    }

    /**
     * 公开线路详情，对齐契约 {@code GET /routes/{routeId}}（RouteDetailEnvelope）。
     *
     * @param currentUserId 当前登录用户，未登录传 null（仅影响 favorite 字段）
     */
    public RouteDetailView detail(Long routeId, Long currentUserId) {
        TravelRoute route = routeMapper.selectById(routeId);
        if (route == null || !RouteStatus.PUBLISHED.equals(route.status) || Integer.valueOf(1).equals(route.deleted)) {
            throw new BusinessException(404, "线路不存在或暂未上架");
        }
        return new RouteDetailView(
                routeView(route, currentUserId),
                publicDepartures(route),
                // 行程结构与酒店联查逻辑与后台完全一致，直接复用，避免两处各维护一份。
                adminRouteService.itineraryDays(routeId),
                orderService.routeVisibleReviews(routeId),
                isFavorited(currentUserId, routeId));
    }

    // ------------------------------------------------------------------
    // 私有辅助：查询与视图组装
    // ------------------------------------------------------------------

    private PageResponse<RouteSummaryView> toSummaryPage(Page<TravelRoute> result, Long currentUserId) {
        List<TravelRoute> records = result.getRecords();
        List<Long> routeIds = records.stream().map(route -> route.id).toList();
        Map<Long, BigDecimal> minPrices = minAdultPriceMap(routeIds);
        Map<Long, Departure> nextDepartures = nextOpenDepartureMap(routeIds);
        Set<Long> favorites = favoriteRouteIds(currentUserId, routeIds);
        List<RouteSummaryView> items = records.stream()
                .map(route -> summaryOf(route, minPrices, nextDepartures, favorites.contains(route.id)))
                .toList();
        return new PageResponse<>(items, (int) result.getCurrent(), (int) result.getSize(),
                (int) result.getTotal(), (int) result.getPages());
    }

    private RouteSummaryView summaryOf(TravelRoute route, Map<Long, BigDecimal> minPrices,
                                       Map<Long, Departure> nextDepartures, boolean favorite) {
        Departure next = nextDepartures.get(route.id);
        return RouteSummaryView.from(route, minPrices.get(route.id),
                next == null ? null : next.startDate,
                next == null ? null : DepartureView.availableSeats(next),
                favorite);
    }

    private RouteView routeView(TravelRoute route, Long currentUserId) {
        List<Long> routeIds = List.of(route.id);
        Departure next = nextOpenDepartureMap(routeIds).get(route.id);
        return RouteView.from(route, minAdultPriceMap(routeIds).get(route.id),
                next == null ? null : next.startDate,
                next == null ? null : DepartureView.availableSeats(next),
                isFavorited(currentUserId, route.id));
    }

    /** 公开详情只展示仍可报名的团期，避免用户看到已过期或已关闭的团期。 */
    private List<DepartureView> publicDepartures(TravelRoute route) {
        List<Departure> departures = departureMapper.selectList(new QueryWrapper<Departure>()
                .eq("route_id", route.id).eq("status", "OPEN").ge("start_date", LocalDate.now())
                .orderByAsc("start_date"));
        Map<Long, String> guideNames = guideNameMap(departures.stream().map(d -> d.guideId).toList());
        return departures.stream()
                .map(d -> DepartureView.from(d, route.name, lookup(guideNames, d.guideId)))
                .toList();
    }

    private void applyPublicSort(QueryWrapper<TravelRoute> wrapper, String sort) {
        if (sort == null || sort.isBlank()) {
            wrapper.orderByDesc("created_at");
            return;
        }
        String value = sort.trim();
        if (!PUBLIC_SORTS.contains(value)) {
            throw new BusinessException(400, "BAD_REQUEST",
                    "sort 只支持 minAdultPrice,asc、minAdultPrice,desc、validBookingCount,desc、ratingAvg,desc");
        }
        switch (value) {
            case "minAdultPrice,asc", "minAdultPrice,desc" -> {
                // 先按"有没有在售团期"分组，让无价线路恒定排在最后；
                // 再按价格排序，最后用创建时间兜底保证顺序稳定。
                wrapper.select("travel_route.*",
                        MIN_ADULT_PRICE_SQL + " IS NULL AS sortPriceMissing",
                        MIN_ADULT_PRICE_SQL + " AS sortPrice");
                wrapper.orderByAsc("sortPriceMissing");
                if ("minAdultPrice,asc".equals(value)) {
                    wrapper.orderByAsc("sortPrice");
                } else {
                    wrapper.orderByDesc("sortPrice");
                }
                wrapper.orderByDesc("created_at");
            }
            case "validBookingCount,desc" -> wrapper.orderByDesc("valid_booking_count", "created_at");
            case "ratingAvg,desc" -> wrapper.orderByDesc("rating_avg", "created_at");
            default -> wrapper.orderByDesc("created_at");
        }
    }

    /** 在售（OPEN）团期成人价的最小值，与后台线路列表口径一致。 */
    private Map<Long, BigDecimal> minAdultPriceMap(List<Long> routeIds) {
        if (routeIds.isEmpty()) {
            return Map.of();
        }
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
        return prices;
    }

    /** 每条线路最近一个仍可报名的在售团期，用于 nextDepartureDate 与 availableSeats。 */
    private Map<Long, Departure> nextOpenDepartureMap(List<Long> routeIds) {
        if (routeIds.isEmpty()) {
            return Map.of();
        }
        List<Departure> departures = departureMapper.selectList(new QueryWrapper<Departure>()
                .in("route_id", routeIds).eq("status", "OPEN").ge("start_date", LocalDate.now())
                .orderByAsc("start_date"));
        Map<Long, Departure> result = new LinkedHashMap<>();
        departures.forEach(departure -> result.putIfAbsent(departure.routeId, departure));
        return result;
    }

    private Map<Long, String> guideNameMap(Collection<Long> guideIds) {
        List<Long> ids = distinctIds(guideIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return guideMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(guide -> guide.id, guide -> guide.name, (a, b) -> a));
    }

    private Set<Long> favoriteRouteIds(Long currentUserId, List<Long> routeIds) {
        if (currentUserId == null || routeIds.isEmpty()) {
            return Set.of();
        }
        return favoriteMapper.selectList(new QueryWrapper<Favorite>()
                        .eq("user_id", currentUserId).in("route_id", routeIds))
                .stream().map(favorite -> favorite.routeId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean isFavorited(Long currentUserId, Long routeId) {
        return currentUserId != null && favoriteMapper.selectCount(new QueryWrapper<Favorite>()
                .eq("user_id", currentUserId).eq("route_id", routeId)) > 0;
    }

    private static List<Long> distinctIds(Collection<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
    }

    /**
     * 联查结果的安全取值。
     *
     * <p>不能用 {@code map.get(null)} 直接取：本类在"没有任何关联数据"时返回
     * {@link Map#of()}，而不可变集合的 {@code get(null)} 会抛 {@link NullPointerException}
     * （{@code HashMap.get(null)} 才返回 null）。团期未分配导游会走到这里。</p>
     */
    private static String lookup(Map<Long, String> values, Long key) {
        return key == null ? null : values.get(key);
    }
}
