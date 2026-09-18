package com.travelagency.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.enums.RouteStatus;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.DepartureView;
import com.travelagency.domain.dto.ItineraryDayRequest;
import com.travelagency.domain.dto.ItineraryDayView;
import com.travelagency.domain.dto.ItineraryItemRequest;
import com.travelagency.domain.dto.ItineraryItemView;
import com.travelagency.domain.dto.RouteDetailView;
import com.travelagency.domain.dto.RouteSummaryView;
import com.travelagency.domain.dto.RouteUpsertRequest;
import com.travelagency.domain.dto.RouteView;
import com.travelagency.domain.entity.Attraction;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.Guide;
import com.travelagency.domain.entity.Hotel;
import com.travelagency.domain.entity.RouteItineraryDay;
import com.travelagency.domain.entity.RouteItineraryItem;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.AttractionMapper;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.GuideMapper;
import com.travelagency.domain.mapper.HotelMapper;
import com.travelagency.domain.mapper.RouteItineraryDayMapper;
import com.travelagency.domain.mapper.RouteItineraryItemMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 旅行社后台「线路管理 + 行程管理」业务服务，实现契约 Admin Routes 一组端点。
 *
 * <p>职责边界：
 * <ul>
 *   <li>线路：分页查询、详情、创建草稿、修改基本资料、上架/下架；</li>
 *   <li>行程：每日行程与行程项目的新增、修改、删除；</li>
 *   <li>所有对外结果都组装成契约视图（{@link RouteSummaryView} / {@link RouteView} /
 *       {@link RouteDetailView} / {@link ItineraryDayView} / {@link ItineraryItemView}），
 *       不直接序列化数据库实体。</li>
 * </ul></p>
 *
 * <p>服务端强制保证的业务规则（前端无法绕过）：
 * <ol>
 *   <li>线路状态只由服务端流转：新建一律 DRAFT，上下架只接受 PUBLISHED / OFFLINE；</li>
 *   <li>没有每日行程的线路不允许上架（409），避免出现不可销售的线路；</li>
 *   <li>已上架线路的行程结构不允许增删（409），需要先下架，避免销售中的产品被改动；</li>
 *   <li>同一线路的 dayNumber、同一日行程的 sortNo 都必须唯一；</li>
 *   <li>行程引用的酒店、景点必须真实存在，避免外键约束报错或产生脏数据。</li>
 * </ol></p>
 */
@Service
public class AdminRouteService {

    private static final long MAX_PAGE_SIZE = 100;
    private static final List<String> ROUTE_STATUSES =
            List.of(RouteStatus.DRAFT, RouteStatus.PUBLISHED, RouteStatus.OFFLINE);
    private static final List<String> ITEM_TYPES =
            List.of("ATTRACTION", "TRANSPORT", "MEAL", "ACTIVITY", "OTHER");

    private final TravelRouteMapper routeMapper;
    private final DepartureMapper departureMapper;
    private final RouteItineraryDayMapper dayMapper;
    private final RouteItineraryItemMapper itemMapper;
    private final GuideMapper guideMapper;
    private final HotelMapper hotelMapper;
    private final AttractionMapper attractionMapper;
    private final OrderService orderService;

    public AdminRouteService(
            TravelRouteMapper routeMapper,
            DepartureMapper departureMapper,
            RouteItineraryDayMapper dayMapper,
            RouteItineraryItemMapper itemMapper,
            GuideMapper guideMapper,
            HotelMapper hotelMapper,
            AttractionMapper attractionMapper,
            OrderService orderService) {
        this.routeMapper = routeMapper;
        this.departureMapper = departureMapper;
        this.dayMapper = dayMapper;
        this.itemMapper = itemMapper;
        this.guideMapper = guideMapper;
        this.hotelMapper = hotelMapper;
        this.attractionMapper = attractionMapper;
        this.orderService = orderService;
    }

    // ------------------------------------------------------------------
    // 线路管理
    // ------------------------------------------------------------------

    /**
     * 后台线路分页查询，对齐契约 GET /admin/routes。
     *
     * <p>keyword 同时匹配线路名称、目的地和出发城市；status 必须是契约 RouteStatus 之一，
     * 传非法值时返回 422，避免静默返回空列表让调用方误判为"没有数据"。</p>
     */
    public PageResponse<RouteSummaryView> page(long page, long size, String keyword, String status) {
        QueryWrapper<TravelRoute> query = new QueryWrapper<TravelRoute>().eq("deleted", 0);
        if (status != null && !status.isBlank()) {
            String value = status.trim();
            if (!ROUTE_STATUSES.contains(value)) {
                throw new BusinessException(422, "VALIDATION_ERROR", "线路状态只能是 DRAFT、PUBLISHED 或 OFFLINE");
            }
            query.eq("status", value);
        }
        if (keyword != null && !keyword.isBlank()) {
            String value = keyword.trim();
            query.and(w -> w.like("name", value).or().like("destination", value).or().like("departure_city", value));
        }
        query.orderByDesc("created_at");
        Page<TravelRoute> result = routeMapper.selectPage(new Page<>(normalizePage(page), normalizeSize(size)), query);
        List<TravelRoute> records = result.getRecords();
        Map<Long, BigDecimal> minPrices = minAdultPriceMap(routeIds(records));
        Map<Long, Departure> nextDepartures = nextOpenDepartureMap(routeIds(records));
        List<RouteSummaryView> items = records.stream()
                .map(route -> RouteSummaryView.from(route,
                        minPrices.get(route.id),
                        nextDeparture(nextDepartures.get(route.id)),
                        availableSeats(nextDepartures.get(route.id)),
                        false))
                .toList();
        return new PageResponse<>(items, (int) result.getCurrent(), (int) result.getSize(),
                (int) result.getTotal(), (int) result.getPages());
    }

    /** 后台线路详情，对齐契约 GET /admin/routes/{routeId}（RouteDetailEnvelope）。 */
    public RouteDetailView detail(Long routeId) {
        TravelRoute route = requireRoute(routeId);
        return new RouteDetailView(
                toRouteView(route),
                routeDepartures(route),
                itineraryDays(routeId),
                orderService.routeReviewsForAdmin(routeId),
                // 后台是运营视角，不存在"当前用户是否收藏"的语义，契约要求该字段存在，固定为 false。
                false);
    }

    /** 单条线路的契约视图，供导游端等其它模块复用，避免各自重复拼装线路字段。 */
    public RouteView routeView(Long routeId) {
        return toRouteView(requireRoute(routeId));
    }

    /**
     * 创建线路草稿，对齐契约 POST /admin/routes。
     *
     * <p>状态、评分、报名人次由服务端初始化，客户端提交这些字段会被未知字段校验拒绝。</p>
     */
    @Transactional
    public RouteView create(RouteUpsertRequest request, Long operatorId) {
        TravelRoute route = new TravelRoute();
        applyEditableFields(route, request);
        route.status = RouteStatus.DRAFT;
        route.ratingAvg = BigDecimal.ZERO;
        route.ratingCount = 0;
        route.validBookingCount = 0;
        route.createdBy = operatorId;
        route.deleted = 0;
        routeMapper.insert(route);
        // 回查以带回数据库默认值（created_at / updated_at）并保证响应与契约一致。
        return toRouteView(requireRoute(route.id));
    }

    /**
     * 修改线路基本资料，对齐契约 PUT /admin/routes/{routeId}。
     *
     * <p>只覆盖可编辑字段，并且允许把 description / coverUrl 等可空字段清空；
     * 状态、评分、报名人次不在请求契约内，不会被这次修改影响。</p>
     */
    @Transactional
    public RouteView update(Long routeId, RouteUpsertRequest request) {
        requireRoute(routeId);
        routeMapper.update(null, new UpdateWrapper<TravelRoute>().eq("id", routeId)
                .set("name", trimToNull(request.name()))
                .set("departure_city", trimToNull(request.departureCity()))
                .set("destination", trimToNull(request.destination()))
                .set("duration_days", request.durationDays())
                .set("description", trimToNull(request.description()))
                .set("cover_url", trimToNull(request.coverUrl()))
                .set("included", trimToNull(request.included()))
                .set("excluded", trimToNull(request.excluded()))
                .set("booking_notice", trimToNull(request.bookingNotice())));
        return toRouteView(requireRoute(routeId));
    }

    /**
     * 上架 / 下架线路，对齐契约 PATCH /admin/routes/{routeId}/status。
     *
     * <p>状态取值非法返回 422；上架前必须已经维护至少一天行程，否则返回 409，
     * 避免上架一条无法销售的线路。重复上架/下架是幂等的，直接返回当前线路。</p>
     */
    @Transactional
    public RouteView updateStatus(Long routeId, String status) {
        if (status == null || (!RouteStatus.PUBLISHED.equals(status) && !RouteStatus.OFFLINE.equals(status))) {
            throw new BusinessException(422, "VALIDATION_ERROR", "线路状态只能是 PUBLISHED 或 OFFLINE");
        }
        TravelRoute route = requireRoute(routeId);
        if (RouteStatus.PUBLISHED.equals(status) && !RouteStatus.PUBLISHED.equals(route.status)
                && dayCount(routeId) == 0) {
            throw new BusinessException(409, "ROUTE_STATE_CONFLICT", "线路至少需要一天行程才能上架");
        }
        if (!status.equals(route.status)) {
            routeMapper.update(null, new UpdateWrapper<TravelRoute>().eq("id", routeId).set("status", status));
        }
        return toRouteView(requireRoute(routeId));
    }

    // ------------------------------------------------------------------
    // 每日行程管理
    // ------------------------------------------------------------------

    /** 获取线路的全部每日行程（含行程项目），对齐契约 GET /admin/routes/{routeId}/itinerary-days。 */
    public List<ItineraryDayView> itineraryDays(Long routeId) {
        List<RouteItineraryDay> days = dayMapper.selectList(new QueryWrapper<RouteItineraryDay>()
                .eq("route_id", routeId).orderByAsc("day_number"));
        if (days.isEmpty()) {
            return List.of();
        }
        Map<Long, List<RouteItineraryItem>> itemsByDay = itemsByDay(days.stream().map(day -> day.id).toList());
        Map<Long, String> hotelNames = hotelNameMap(days.stream().map(day -> day.hotelId).toList());
        return days.stream()
                .map(day -> toDayView(day, itemsByDay.getOrDefault(day.id, List.of()),
                        lookup(hotelNames, day.hotelId)))
                .toList();
    }

    /** 新增每日行程，对齐契约 POST /admin/routes/{routeId}/itinerary-days（201 + Location）。 */
    @Transactional
    public ItineraryDayView createDay(Long routeId, ItineraryDayRequest request) {
        TravelRoute route = requireRoute(routeId);
        if (RouteStatus.PUBLISHED.equals(route.status)) {
            throw new BusinessException(409, "ROUTE_STATE_CONFLICT", "线路已上架，请先下架再调整行程结构");
        }
        Hotel hotel = requireHotel(request.hotelId());
        if (dayNumberExists(routeId, request.dayNumber(), null)) {
            throw new BusinessException(409, "ROUTE_STATE_CONFLICT",
                    "第 " + request.dayNumber() + " 天行程已存在，同一线路的行程天数不能重复");
        }
        RouteItineraryDay day = new RouteItineraryDay();
        day.routeId = routeId;
        day.dayNumber = request.dayNumber();
        day.title = trimToNull(request.title());
        day.description = trimToNull(request.description());
        day.transportation = trimToNull(request.transportation());
        day.meals = trimToNull(request.meals());
        day.hotelId = request.hotelId();
        dayMapper.insert(day);
        return toDayView(dayMapper.selectById(day.id), List.of(), hotel == null ? null : hotel.name);
    }

    /** 修改每日行程，对齐契约 PUT /admin/itinerary-days/{dayId}。 */
    @Transactional
    public ItineraryDayView updateDay(Long dayId, ItineraryDayRequest request) {
        RouteItineraryDay day = requireDay(dayId);
        Hotel hotel = requireHotel(request.hotelId());
        if (dayNumberExists(day.routeId, request.dayNumber(), dayId)) {
            // 契约为该端点只列出 200 / 404；同一线路内天数序号冲突属于字段语义校验失败，
            // 按 docs/API.md 第 7 节返回 422，而不是数据库唯一键报错后的 500。
            throw new BusinessException(422, "VALIDATION_ERROR",
                    "第 " + request.dayNumber() + " 天行程已存在，同一线路的行程天数不能重复");
        }
        dayMapper.update(null, new UpdateWrapper<RouteItineraryDay>().eq("id", dayId)
                .set("day_number", request.dayNumber())
                .set("title", trimToNull(request.title()))
                .set("description", trimToNull(request.description()))
                .set("transportation", trimToNull(request.transportation()))
                .set("meals", trimToNull(request.meals()))
                .set("hotel_id", request.hotelId()));
        return toDayView(requireDay(dayId), itemsOf(dayId), hotel == null ? null : hotel.name);
    }

    /**
     * 删除每日行程及其行程项目，对齐契约 DELETE /admin/itinerary-days/{dayId}（204）。
     *
     * <p>已上架线路的行程结构不允许删除（409），避免改动正在销售的产品。</p>
     */
    @Transactional
    public void deleteDay(Long dayId) {
        RouteItineraryDay day = requireDay(dayId);
        TravelRoute route = requireRoute(day.routeId);
        if (RouteStatus.PUBLISHED.equals(route.status)) {
            throw new BusinessException(409, "ROUTE_STATE_CONFLICT", "线路已上架，请先下架再调整行程结构");
        }
        // 先删项目再删当天，避免留下悬挂的行程项目（外键要求先删子记录）。
        itemMapper.delete(new QueryWrapper<RouteItineraryItem>().eq("day_id", dayId));
        dayMapper.deleteById(dayId);
    }

    // ------------------------------------------------------------------
    // 行程项目管理
    // ------------------------------------------------------------------

    /** 获取某日行程项目，对齐契约 GET /admin/itinerary-days/{dayId}/items。 */
    public List<ItineraryItemView> items(Long dayId) {
        requireDay(dayId);
        return itemsOf(dayId).stream().map(ItineraryItemView::from).toList();
    }

    /** 新增行程项目，对齐契约 POST /admin/itinerary-days/{dayId}/items（201 + Location）。 */
    @Transactional
    public ItineraryItemView createItem(Long dayId, ItineraryItemRequest request) {
        RouteItineraryDay day = requireDay(dayId);
        requireItemType(request.itemType());
        Attraction attraction = requireAttraction(request.attractionId());
        if (sortNoExists(dayId, request.sortNo(), null)) {
            throw new BusinessException(422, "VALIDATION_ERROR", "排序号 " + request.sortNo() + " 已存在，请调整排序");
        }
        RouteItineraryItem item = new RouteItineraryItem();
        item.dayId = dayId;
        applyItemFields(item, request, attraction);
        itemMapper.insert(item);
        // 回查以带回数据库默认值，返回与契约一致的视图。
        return ItineraryItemView.from(itemMapper.selectById(item.id));
    }

    /** 修改行程项目，对齐契约 PUT /admin/itinerary-items/{itemId}。 */
    @Transactional
    public ItineraryItemView updateItem(Long itemId, ItineraryItemRequest request) {
        RouteItineraryItem item = requireItem(itemId);
        requireItemType(request.itemType());
        Attraction attraction = requireAttraction(request.attractionId());
        if (sortNoExists(item.dayId, request.sortNo(), itemId)) {
            throw new BusinessException(422, "VALIDATION_ERROR", "排序号 " + request.sortNo() + " 已存在，请调整排序");
        }
        itemMapper.update(null, new UpdateWrapper<RouteItineraryItem>().eq("id", itemId)
                .set("sort_no", request.sortNo())
                .set("item_type", request.itemType())
                .set("name", trimToNull(request.name()))
                .set("description", trimToNull(request.description()))
                .set("attraction_id", request.attractionId())
                .set("longitude", coordinates(request.longitude(), attraction == null ? null : attraction.longitude))
                .set("latitude", coordinates(request.latitude(), attraction == null ? null : attraction.latitude)));
        return ItineraryItemView.from(requireItem(itemId));
    }

    /** 删除行程项目，对齐契约 DELETE /admin/itinerary-items/{itemId}（204）。 */
    @Transactional
    public void deleteItem(Long itemId) {
        requireItem(itemId);
        itemMapper.deleteById(itemId);
    }

    // ------------------------------------------------------------------
    // 私有辅助：视图组装
    // ------------------------------------------------------------------

    private RouteView toRouteView(TravelRoute route) {
        List<Long> ids = List.of(route.id);
        Departure next = nextOpenDepartureMap(ids).get(route.id);
        return RouteView.from(route, minAdultPriceMap(ids).get(route.id),
                nextDeparture(next), availableSeats(next), false);
    }

    /** 线路的团期列表（后台视角：含全部未删除团期，按出发日期升序）。 */
    private List<DepartureView> routeDepartures(TravelRoute route) {
        List<Departure> departures = departureMapper.selectList(new QueryWrapper<Departure>()
                .eq("route_id", route.id).orderByAsc("start_date"));
        Map<Long, String> guideNames = guideNameMap(departures.stream().map(d -> d.guideId).toList());
        return departures.stream()
                .map(d -> DepartureView.from(d, route.name, lookup(guideNames, d.guideId)))
                .toList();
    }

    private ItineraryDayView toDayView(RouteItineraryDay day, List<RouteItineraryItem> items, String hotelName) {
        return new ItineraryDayView(day.id, day.routeId, day.dayNumber, day.title, day.description,
                day.transportation, day.meals, day.hotelId, hotelName,
                items.stream().map(ItineraryItemView::from).toList());
    }

    /** 可售最低价：在售（OPEN）团期成人价的最小值，与公开线路列表口径一致。 */
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

    private Map<Long, List<RouteItineraryItem>> itemsByDay(List<Long> dayIds) {
        if (dayIds.isEmpty()) {
            return Map.of();
        }
        List<RouteItineraryItem> items = itemMapper.selectList(new QueryWrapper<RouteItineraryItem>()
                .in("day_id", dayIds).orderByAsc("sort_no"));
        return items.stream().collect(Collectors.groupingBy(item -> item.dayId, LinkedHashMap::new,
                Collectors.toList()));
    }

    private List<RouteItineraryItem> itemsOf(Long dayId) {
        return itemMapper.selectList(new QueryWrapper<RouteItineraryItem>()
                .eq("day_id", dayId).orderByAsc("sort_no"));
    }

    private Map<Long, String> hotelNameMap(Collection<Long> hotelIds) {
        List<Long> ids = distinctIds(hotelIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return hotelMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(hotel -> hotel.id, hotel -> hotel.name, (a, b) -> a));
    }

    private Map<Long, String> guideNameMap(Collection<Long> guideIds) {
        List<Long> ids = distinctIds(guideIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return guideMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(guide -> guide.id, guide -> guide.name, (a, b) -> a));
    }

    private static LocalDate nextDeparture(Departure departure) {
        return departure == null ? null : departure.startDate;
    }

    /**
     * 联查结果的安全取值。
     *
     * <p>不能用 {@code map.get(null)} 直接取：本类在"没有任何关联数据"时返回
     * {@link Map#of()}，而不可变集合的 {@code get(null)} 会抛 {@link NullPointerException}
     * （{@code HashMap.get(null)} 才返回 null）。行程未安排酒店、团期未分配导游都会走到这里，
     * 一旦漏判就会让线路详情/行程接口 500。</p>
     */
    private static String lookup(Map<Long, String> values, Long key) {
        return key == null ? null : values.get(key);
    }

    private static Integer availableSeats(Departure departure) {
        return departure == null ? null : DepartureView.availableSeats(departure);
    }

    // ------------------------------------------------------------------
    // 私有辅助：校验与赋值
    // ------------------------------------------------------------------

    private void applyEditableFields(TravelRoute route, RouteUpsertRequest request) {
        route.name = trimToNull(request.name());
        route.departureCity = trimToNull(request.departureCity());
        route.destination = trimToNull(request.destination());
        route.durationDays = request.durationDays();
        route.description = trimToNull(request.description());
        route.coverUrl = trimToNull(request.coverUrl());
        route.included = trimToNull(request.included());
        route.excluded = trimToNull(request.excluded());
        route.bookingNotice = trimToNull(request.bookingNotice());
    }

    private void applyItemFields(RouteItineraryItem item, ItineraryItemRequest request, Attraction attraction) {
        item.sortNo = request.sortNo();
        item.itemType = request.itemType();
        item.name = trimToNull(request.name());
        item.description = trimToNull(request.description());
        item.attractionId = request.attractionId();
        item.longitude = coordinates(request.longitude(), attraction == null ? null : attraction.longitude);
        item.latitude = coordinates(request.latitude(), attraction == null ? null : attraction.latitude);
    }

    /**
     * 项目经纬度：优先使用请求值；未填写时沿用所关联景点的坐标，
     * 保证"景点坐标"数据可以直接用于行程地图展示。
     */
    private static BigDecimal coordinates(Double requested, BigDecimal fromAttraction) {
        return requested == null ? fromAttraction : BigDecimal.valueOf(requested);
    }

    private TravelRoute requireRoute(Long routeId) {
        TravelRoute route = routeMapper.selectById(routeId);
        if (route == null || Integer.valueOf(1).equals(route.deleted)) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "线路不存在");
        }
        return route;
    }

    private RouteItineraryDay requireDay(Long dayId) {
        RouteItineraryDay day = dayMapper.selectById(dayId);
        if (day == null) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "每日行程不存在");
        }
        return day;
    }

    private RouteItineraryItem requireItem(Long itemId) {
        RouteItineraryItem item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "行程项目不存在");
        }
        return item;
    }

    private Hotel requireHotel(Long hotelId) {
        if (hotelId == null) {
            return null;
        }
        Hotel hotel = hotelMapper.selectById(hotelId);
        if (hotel == null) {
            throw new BusinessException(422, "VALIDATION_ERROR", "指定的酒店不存在");
        }
        return hotel;
    }

    private Attraction requireAttraction(Long attractionId) {
        if (attractionId == null) {
            return null;
        }
        Attraction attraction = attractionMapper.selectById(attractionId);
        if (attraction == null) {
            throw new BusinessException(422, "VALIDATION_ERROR", "指定的景点不存在");
        }
        return attraction;
    }

    /** 项目类型白名单校验：DTO 已做 @Pattern，此处兜底防止绕过请求校验的调用方写入非法值。 */
    private void requireItemType(String itemType) {
        if (itemType == null || !ITEM_TYPES.contains(itemType)) {
            throw new BusinessException(422, "VALIDATION_ERROR",
                    "行程项目类型只能是 ATTRACTION、TRANSPORT、MEAL、ACTIVITY 或 OTHER");
        }
    }

    private boolean dayNumberExists(Long routeId, Integer dayNumber, Long excludeDayId) {
        QueryWrapper<RouteItineraryDay> query = new QueryWrapper<RouteItineraryDay>()
                .eq("route_id", routeId).eq("day_number", dayNumber);
        if (excludeDayId != null) {
            query.ne("id", excludeDayId);
        }
        return dayMapper.selectCount(query) > 0;
    }

    private boolean sortNoExists(Long dayId, Integer sortNo, Long excludeItemId) {
        QueryWrapper<RouteItineraryItem> query = new QueryWrapper<RouteItineraryItem>()
                .eq("day_id", dayId).eq("sort_no", sortNo);
        if (excludeItemId != null) {
            query.ne("id", excludeItemId);
        }
        return itemMapper.selectCount(query) > 0;
    }

    private long dayCount(Long routeId) {
        return dayMapper.selectCount(new QueryWrapper<RouteItineraryDay>().eq("route_id", routeId));
    }

    private static List<Long> routeIds(List<TravelRoute> routes) {
        return routes.stream().map(route -> route.id).filter(Objects::nonNull).distinct().toList();
    }

    private static List<Long> distinctIds(Collection<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        ids.stream().filter(Objects::nonNull).distinct().forEach(result::add);
        return result;
    }

    /** 去掉首尾空白；空白字符串按"未填写"处理，统一存 NULL，避免同一含义出现两种表示。 */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static long normalizePage(long page) {
        return Math.max(page, 1);
    }

    private static long normalizeSize(long size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }
}
