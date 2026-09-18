package com.travelagency.domain.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.audit.OperationLogRecorder;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.DepartureView;
import com.travelagency.domain.dto.ItineraryDayRequest;
import com.travelagency.domain.dto.ItineraryDayView;
import com.travelagency.domain.dto.ItineraryItemRequest;
import com.travelagency.domain.dto.ItineraryItemView;
import com.travelagency.domain.dto.ReviewView;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 成员 C 线路管理模块：{@link AdminRouteService} 业务规则单元测试。
 *
 * <p>不启动 Spring、不连接数据库，全部依赖用 Mockito 替身，因此随普通 {@code mvn test} 执行。
 * 覆盖 docs/openapi.yaml 中 Admin Routes 的契约语义（状态码、字段、视图结构）与
 * 服务端必须保证的业务规则：</p>
 * <ul>
 *   <li>新建线路一律 DRAFT，客户端字段不参与状态决定；</li>
 *   <li>没有每日行程不允许上架（409）；已上架线路的行程结构冻结（409），文案仍可修改；</li>
 *   <li>同一线路 dayNumber 唯一、同一日 sortNo 唯一，非法值返回 422 而不是数据库报错 500；</li>
 *   <li>行程引用的酒店/景点必须存在（422）；项目未填坐标时继承景点坐标；</li>
 *   <li>删除每日行程必须先删行程项目（外键与级联语义）；</li>
 *   <li>视图映射与契约一致（实体字段不外泄、可空联查键不触发 NPE）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminRouteServiceTest {

    /** 模拟登录后台操作人（Controller 传入 CurrentUser.required().userId()）。 */
    private static final long ACTOR = 99L;

    @Mock private TravelRouteMapper routeMapper;
    @Mock private DepartureMapper departureMapper;
    @Mock private RouteItineraryDayMapper dayMapper;
    @Mock private RouteItineraryItemMapper itemMapper;
    @Mock private GuideMapper guideMapper;
    @Mock private HotelMapper hotelMapper;
    @Mock private AttractionMapper attractionMapper;
    @Mock private OrderService orderService;
    @Mock private OperationLogRecorder operationLog;

    private AdminRouteService service;

    @BeforeEach
    void setUp() {
        service = new AdminRouteService(routeMapper, departureMapper, dayMapper, itemMapper,
                guideMapper, hotelMapper, attractionMapper, orderService, operationLog);
    }

    // ------------------------------------------------------------------
    // 线路列表
    // ------------------------------------------------------------------

    @Test
    @DisplayName("列表：status 不在契约枚举内时返回 422，且不访问数据库")
    void pageRejectsStatusOutsideContractEnum() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.page(1, 20, null, "NOT_A_STATUS"));

        assertEquals(422, ex.getStatus());
        assertEquals("VALIDATION_ERROR", ex.getCode());
        verifyNoInteractions(routeMapper);
    }

    @Test
    @DisplayName("列表：归档为 RouteSummary，最低价/最近出发/余位来自在售团期")
    void pageMapsRouteSummaryFields() {
        TravelRoute route = route(1L, "云南 6 日", "PUBLISHED");
        route.ratingAvg = new BigDecimal("4.80");
        route.ratingCount = 26;
        route.validBookingCount = 132;
        when(routeMapper.selectPage(any(), any())).thenReturn(pageOf(List.of(route), 32));
        when(departureMapper.selectMaps(any())).thenReturn(List.of(priceRow(1L, "2999.00")));
        when(departureMapper.selectList(any())).thenReturn(List.of(departure(1L, LocalDate.now().plusDays(10), 30, 4, 8)));

        PageResponse<RouteSummaryView> result = service.page(1, 20, null, "PUBLISHED");

        assertEquals(1, result.items().size());
        assertEquals(32, result.total());
        RouteSummaryView item = result.items().get(0);
        assertEquals("1", String.valueOf(item.id()));
        assertEquals("云南 6 日", item.name());
        assertEquals("上海", item.departureCity());
        assertEquals(new BigDecimal("2999.00"), item.minAdultPrice());
        assertEquals(LocalDate.now().plusDays(10), item.nextDepartureDate());
        assertEquals(18, item.availableSeats(), "余位 = 最大人数 - 已预留 - 已确认");
        assertEquals(new BigDecimal("4.80"), item.ratingAvg());
        assertEquals(26, item.ratingCount());
        assertEquals(132, item.validBookingCount());
        assertEquals("PUBLISHED", item.status());
        assertTrue(!item.favorite(), "后台列表不涉及当前用户收藏语义");
    }

    @Test
    @DisplayName("列表：历史数据 ratingAvg/ratingCount 为 null 时按契约必填补 0")
    void pageCoercesNullRatingFields() {
        TravelRoute route = route(2L, "无评分线路", "DRAFT");
        route.ratingAvg = null;
        route.ratingCount = null;
        route.validBookingCount = null;
        when(routeMapper.selectPage(any(), any())).thenReturn(pageOf(List.of(route), 1));
        when(departureMapper.selectMaps(any())).thenReturn(List.of());
        when(departureMapper.selectList(any())).thenReturn(List.of());

        RouteSummaryView item = service.page(1, 20, null, null).items().get(0);

        assertEquals(BigDecimal.ZERO, item.ratingAvg());
        assertEquals(0, item.ratingCount());
        assertEquals(0, item.validBookingCount());
        assertNull(item.minAdultPrice(), "没有在售团期时最低价为空");
        assertNull(item.nextDepartureDate());
        assertNull(item.availableSeats());
    }

    @Test
    @DisplayName("列表：分页参数被归一化（page 下限 1，size 上限 100）")
    void pageNormalizesPaging() {
        when(routeMapper.selectPage(any(), any())).thenReturn(pageOf(List.of(), 0));
        when(departureMapper.selectMaps(any())).thenReturn(List.of());
        when(departureMapper.selectList(any())).thenReturn(List.of());

        service.page(0, 5000, "云南", "PUBLISHED");

        ArgumentCaptor<Page<TravelRoute>> captor = ArgumentCaptor.forClass(Page.class);
        verify(routeMapper).selectPage(captor.capture(), any());
        assertEquals(1, captor.getValue().getCurrent());
        assertEquals(100, captor.getValue().getSize());
    }

    // ------------------------------------------------------------------
    // 创建 / 修改线路
    // ------------------------------------------------------------------

    @Test
    @DisplayName("创建：状态、评分、报名人次与创建人由服务端决定")
    void createForcesDraftAndServerOwnedFields() {
        TravelRoute persisted = new TravelRoute();
        persisted.id = 7L;
        when(routeMapper.insert(any(TravelRoute.class))).thenAnswer(invocation -> {
            TravelRoute inserted = invocation.getArgument(0);
            persisted.name = inserted.name;
            persisted.status = inserted.status;
            persisted.ratingAvg = inserted.ratingAvg;
            persisted.ratingCount = inserted.ratingCount;
            persisted.validBookingCount = inserted.validBookingCount;
            persisted.createdBy = inserted.createdBy;
            persisted.deleted = inserted.deleted;
            inserted.id = 7L;
            return 1;
        });
        when(routeMapper.selectById(7L)).thenReturn(persisted);
        when(departureMapper.selectMaps(any())).thenReturn(List.of());
        when(departureMapper.selectList(any())).thenReturn(List.of());

        RouteView view = service.create(upsert("  云南 6 日  ", "上海", "云南", 6), ACTOR);

        ArgumentCaptor<TravelRoute> captor = ArgumentCaptor.forClass(TravelRoute.class);
        verify(routeMapper).insert(captor.capture());
        TravelRoute saved = captor.getValue();
        assertEquals("DRAFT", saved.status);
        assertEquals(BigDecimal.ZERO, saved.ratingAvg);
        assertEquals(0, saved.ratingCount, "评分数量初始化为 0");
        assertEquals(0, saved.validBookingCount, "有效报名人次初始化为 0");
        assertEquals(99L, saved.createdBy);
        assertEquals(0, saved.deleted);
        assertEquals("云南 6 日", saved.name, "名称首尾空白应被去除");
        assertEquals("DRAFT", view.status());
        assertEquals(7L, view.id());
    }

    @Test
    @DisplayName("创建：未填写的可选字段存 NULL，而不是空字符串")
    void createStoresBlankOptionalFieldsAsNull() {
        when(routeMapper.insert(any(TravelRoute.class))).thenAnswer(invocation -> {
            TravelRoute inserted = invocation.getArgument(0);
            inserted.id = 8L;
            return 1;
        });
        when(routeMapper.selectById(8L)).thenReturn(route(8L, "线路", "DRAFT"));
        when(departureMapper.selectMaps(any())).thenReturn(List.of());
        when(departureMapper.selectList(any())).thenReturn(List.of());

        RouteUpsertRequest request = new RouteUpsertRequest(
                "线路", "上海", "云南", 3, "   ", "  ", "", null, " 须知 ");
        service.create(request, ACTOR);

        ArgumentCaptor<TravelRoute> captor = ArgumentCaptor.forClass(TravelRoute.class);
        verify(routeMapper).insert(captor.capture());
        assertNull(captor.getValue().description);
        assertNull(captor.getValue().coverUrl);
        assertNull(captor.getValue().included);
        assertEquals("须知", captor.getValue().bookingNotice, "有内容时保留去除空白后的值");
    }

    @Test
    @DisplayName("修改：只更新可编辑字段，允许清空可空字段，且不触碰状态")
    void updateOnlyTouchesEditableFields() {
        TravelRoute route = route(3L, "旧名称", "PUBLISHED");
        when(routeMapper.selectById(3L)).thenReturn(route);
        when(departureMapper.selectMaps(any())).thenReturn(List.of());
        when(departureMapper.selectList(any())).thenReturn(List.of());

        service.update(3L, new RouteUpsertRequest("新名称", "北京", "新疆", 8, null, null, null, null, null), ACTOR);

        ArgumentCaptor<Wrapper<TravelRoute>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(routeMapper).update(isNull(), captor.capture());
        String setSql = ((UpdateWrapper<TravelRoute>) captor.getValue()).getSqlSet();
        assertTrue(setSql.contains("name"), "应更新 name");
        assertTrue(setSql.contains("description"), "应更新可空字段，以便 PUT 能清空");
        assertTrue(setSql.contains("booking_notice"));
        assertTrue(!setSql.contains("status"), "状态由上下架接口决定，资料修改不得改动 status");
        assertTrue(!setSql.contains("rating_avg"), "评分不由资料修改写入");
    }

    @Test
    @DisplayName("修改：线路不存在或已逻辑删除时返回 404")
    void updateMissingRouteReturnsNotFound() {
        TravelRoute deleted = route(4L, "已删除", "DRAFT");
        deleted.deleted = 1;
        when(routeMapper.selectById(4L)).thenReturn(deleted);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(4L, upsert("线路", "上海", "云南", 3), ACTOR));

        assertEquals(404, ex.getStatus());
        verify(routeMapper, never()).update(any(), any());
    }

    // ------------------------------------------------------------------
    // 上架 / 下架
    // ------------------------------------------------------------------

    @Test
    @DisplayName("上下架：只接受 PUBLISHED / OFFLINE，非法值 422 且不访问数据库")
    void updateStatusRejectsOtherStatuses() {
        for (String invalid : new String[]{null, "DRAFT", "ON_SALE", ""}) {
            BusinessException ex = assertThrows(BusinessException.class, () -> service.updateStatus(1L, invalid, ACTOR));
            assertEquals(422, ex.getStatus());
            assertEquals("VALIDATION_ERROR", ex.getCode());
        }
        verifyNoInteractions(routeMapper);
    }

    @Test
    @DisplayName("上下架：没有每日行程的线路不允许上架（409）")
    void publishWithoutItineraryIsRejected() {
        when(routeMapper.selectById(5L)).thenReturn(route(5L, "空线路", "DRAFT"));
        when(dayMapper.selectCount(any())).thenReturn(0L);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.updateStatus(5L, "PUBLISHED", ACTOR));

        assertEquals(409, ex.getStatus());
        assertEquals("ROUTE_STATE_CONFLICT", ex.getCode());
        verify(routeMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("上下架：有行程时上架成功并写入 PUBLISHED")
    void publishWithItinerarySucceeds() {
        TravelRoute draft = route(6L, "可售线路", "DRAFT");
        TravelRoute published = route(6L, "可售线路", "PUBLISHED");
        when(routeMapper.selectById(6L)).thenReturn(draft, published);
        when(dayMapper.selectCount(any())).thenReturn(3L);
        when(departureMapper.selectMaps(any())).thenReturn(List.of());
        when(departureMapper.selectList(any())).thenReturn(List.of());

        RouteView view = service.updateStatus(6L, "PUBLISHED", ACTOR);

        ArgumentCaptor<Wrapper<TravelRoute>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(routeMapper).update(isNull(), captor.capture());
        assertTrue(((UpdateWrapper<TravelRoute>) captor.getValue()).getSqlSet().contains("status"));
        assertEquals("PUBLISHED", view.status());
    }

    @Test
    @DisplayName("上下架：重复上架是幂等的，不产生多余写操作")
    void publishingAnAlreadyPublishedRouteIsIdempotent() {
        when(routeMapper.selectById(7L)).thenReturn(route(7L, "已上架", "PUBLISHED"));
        when(departureMapper.selectMaps(any())).thenReturn(List.of());
        when(departureMapper.selectList(any())).thenReturn(List.of());

        RouteView view = service.updateStatus(7L, "PUBLISHED", ACTOR);

        assertEquals("PUBLISHED", view.status());
        verify(routeMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("上下架：下架不要求已有行程")
    void offlineDoesNotRequireItinerary() {
        TravelRoute published = route(8L, "已上架", "PUBLISHED");
        TravelRoute offline = route(8L, "已上架", "OFFLINE");
        when(routeMapper.selectById(8L)).thenReturn(published, offline);
        when(departureMapper.selectMaps(any())).thenReturn(List.of());
        when(departureMapper.selectList(any())).thenReturn(List.of());

        assertEquals("OFFLINE", service.updateStatus(8L, "OFFLINE", ACTOR).status());
        verify(dayMapper, never()).selectCount(any());
    }

    // ------------------------------------------------------------------
    // 每日行程
    // ------------------------------------------------------------------

    @Test
    @DisplayName("行程查询：未安排酒店时 hotelName 为 null 且不抛异常（Map.of 取 null 键回归）")
    void itineraryDaysToleratesNullHotelAndEmptyLookups() {
        RouteItineraryDay day = day(11L, 21L, 1, "上海 → 昆明", null);
        when(dayMapper.selectList(any())).thenReturn(List.of(day));
        when(itemMapper.selectList(any())).thenReturn(List.of());
        when(hotelMapper.selectByIds(any())).thenReturn(List.of());

        List<ItineraryDayView> days = service.itineraryDays(21L);

        assertEquals(1, days.size());
        assertNull(days.get(0).hotelId());
        assertNull(days.get(0).hotelName(), "没有酒店时应返回 null，而不是抛 NullPointerException");
        assertTrue(days.get(0).items().isEmpty());
    }

    @Test
    @DisplayName("行程查询：带上酒店名与按排序的行程项目")
    void itineraryDaysCarriesHotelNameAndItems() {
        RouteItineraryDay day = day(11L, 21L, 1, "上海 → 昆明", 5L);
        Hotel hotel = new Hotel();
        hotel.id = 5L;
        hotel.name = "昆明测试酒店";
        RouteItineraryItem item = item(31L, 11L, 1, "ATTRACTION", "大理古城");
        when(dayMapper.selectList(any())).thenReturn(List.of(day));
        when(itemMapper.selectList(any())).thenReturn(List.of(item));
        when(hotelMapper.selectByIds(any())).thenReturn(List.of(hotel));

        ItineraryDayView view = service.itineraryDays(21L).get(0);

        assertEquals("昆明测试酒店", view.hotelName());
        assertEquals(1, view.items().size());
        assertEquals("大理古城", view.items().get(0).name());
    }

    @Test
    @DisplayName("新增行程：已上架线路的行程结构冻结（409），且不写库")
    void createDayOnPublishedRouteIsRejected() {
        when(routeMapper.selectById(21L)).thenReturn(route(21L, "已上架", "PUBLISHED"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createDay(21L, new ItineraryDayRequest(2, "第二天", null, null, null, null), ACTOR));

        assertEquals(409, ex.getStatus());
        assertEquals("ROUTE_STATE_CONFLICT", ex.getCode());
        verify(dayMapper, never()).insert(any(RouteItineraryDay.class));
    }

    @Test
    @DisplayName("新增行程：酒店不存在返回 422，天数序号重复返回 409")
    void createDayValidatesHotelAndDayNumber() {
        when(routeMapper.selectById(21L)).thenReturn(route(21L, "草稿线路", "DRAFT"));
        when(hotelMapper.selectById(999L)).thenReturn(null);
        BusinessException hotelMissing = assertThrows(BusinessException.class,
                () -> service.createDay(21L, new ItineraryDayRequest(1, "第一天", null, null, null, 999L), ACTOR));
        assertEquals(422, hotelMissing.getStatus());

        when(dayMapper.selectCount(any())).thenReturn(1L);
        BusinessException duplicated = assertThrows(BusinessException.class,
                () -> service.createDay(21L, new ItineraryDayRequest(1, "第一天", null, null, null, null), ACTOR));
        assertEquals(409, duplicated.getStatus());
        verify(dayMapper, never()).insert(any(RouteItineraryDay.class));
    }

    @Test
    @DisplayName("新增行程：成功时落库线路归属与文案，并回填酒店名")
    void createDayPersistsRouteBinding() {
        when(routeMapper.selectById(21L)).thenReturn(route(21L, "草稿线路", "DRAFT"));
        Hotel hotel = new Hotel();
        hotel.id = 5L;
        hotel.name = "昆明测试酒店";
        when(hotelMapper.selectById(5L)).thenReturn(hotel);
        when(dayMapper.selectCount(any())).thenReturn(0L);
        when(dayMapper.insert(any(RouteItineraryDay.class))).thenAnswer(invocation -> {
            RouteItineraryDay inserted = invocation.getArgument(0);
            inserted.id = 11L;
            return 1;
        });
        when(dayMapper.selectById(11L)).thenReturn(day(11L, 21L, 1, " 上海 → 昆明 ", 5L));

        ItineraryDayView view = service.createDay(21L,
                new ItineraryDayRequest(1, " 上海 → 昆明 ", " 抵达入住 ", " 飞机 ", " 晚餐 ", 5L), ACTOR);

        ArgumentCaptor<RouteItineraryDay> captor = ArgumentCaptor.forClass(RouteItineraryDay.class);
        verify(dayMapper).insert(captor.capture());
        RouteItineraryDay saved = captor.getValue();
        assertEquals(21L, saved.routeId, "线路归属由 URL 决定");
        assertEquals(1, saved.dayNumber);
        assertEquals("上海 → 昆明", saved.title);
        assertEquals("抵达入住", saved.description);
        assertEquals("昆明测试酒店", view.hotelName());
    }

    @Test
    @DisplayName("修改行程：与同线路其它天冲突返回 422（不是数据库唯一键 500）")
    void updateDayRejectsConflictingDayNumber() {
        when(dayMapper.selectById(11L)).thenReturn(day(11L, 21L, 1, "第一天", null));
        when(dayMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateDay(11L, new ItineraryDayRequest(2, "冲突", null, null, null, null), ACTOR));

        assertEquals(422, ex.getStatus());
        assertEquals("VALIDATION_ERROR", ex.getCode());
        verify(dayMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("修改行程：PUT 可清空描述与酒店，并保留原线路归属")
    void updateDayClearsNullableFields() {
        when(dayMapper.selectById(11L)).thenReturn(day(11L, 21L, 1, "第一天", 5L));
        when(dayMapper.selectCount(any())).thenReturn(0L);
        when(itemMapper.selectList(any())).thenReturn(List.of());

        service.updateDay(11L, new ItineraryDayRequest(2, "第二天", null, null, null, null), ACTOR);

        ArgumentCaptor<Wrapper<RouteItineraryDay>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(dayMapper).update(isNull(), captor.capture());
        UpdateWrapper<RouteItineraryDay> wrapper = (UpdateWrapper<RouteItineraryDay>) captor.getValue();
        assertTrue(wrapper.getSqlSet().contains("hotel_id"), "允许把酒店清空");
        assertTrue(wrapper.getParamNameValuePairs().containsValue(null), "可空字段应写入 NULL");
    }

    @Test
    @DisplayName("删除行程：先级联删除行程项目，再删除当天")
    void deleteDayCascadesItemsFirst() {
        when(dayMapper.selectById(11L)).thenReturn(day(11L, 21L, 1, "第一天", null));
        when(routeMapper.selectById(21L)).thenReturn(route(21L, "草稿线路", "DRAFT"));

        service.deleteDay(11L, ACTOR);

        InOrder order = inOrder(itemMapper, dayMapper);
        order.verify(itemMapper).delete(any());
        order.verify(dayMapper).deleteById(11L);
    }

    @Test
    @DisplayName("删除行程：已上架线路不允许调整结构（409）")
    void deleteDayOnPublishedRouteIsRejected() {
        when(dayMapper.selectById(11L)).thenReturn(day(11L, 21L, 1, "第一天", null));
        when(routeMapper.selectById(21L)).thenReturn(route(21L, "已上架", "PUBLISHED"));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.deleteDay(11L, ACTOR));

        assertEquals(409, ex.getStatus());
        verify(itemMapper, never()).delete(any());
        verify(dayMapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("行程项目：不存在的每日行程返回 404")
    void itemsRequireExistingDay() {
        when(dayMapper.selectById(404L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.items(404L));

        assertEquals(404, ex.getStatus());
        assertEquals("RESOURCE_NOT_FOUND", ex.getCode());
    }

    // ------------------------------------------------------------------
    // 行程项目
    // ------------------------------------------------------------------

    @Test
    @DisplayName("新增项目：类型必须在契约枚举内，景点必须存在（422）")
    void createItemValidatesTypeAndAttraction() {
        when(dayMapper.selectById(11L)).thenReturn(day(11L, 21L, 1, "第一天", null));

        BusinessException badType = assertThrows(BusinessException.class,
                () -> service.createItem(11L, itemRequest(1, "SHOPPING", "购物店", null, null, null), ACTOR));
        assertEquals(422, badType.getStatus());

        when(attractionMapper.selectById(999L)).thenReturn(null);
        BusinessException badAttraction = assertThrows(BusinessException.class,
                () -> service.createItem(11L, itemRequest(1, "ATTRACTION", "大理古城", 999L, null, null), ACTOR));
        assertEquals(422, badAttraction.getStatus());

        verify(itemMapper, never()).insert(any(RouteItineraryItem.class));
    }

    @Test
    @DisplayName("新增项目：同一天排序号重复返回 422")
    void createItemRejectsDuplicateSortNo() {
        when(dayMapper.selectById(11L)).thenReturn(day(11L, 21L, 1, "第一天", null));
        when(itemMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createItem(11L, itemRequest(1, "MEAL", "午餐", null, null, null), ACTOR));

        assertEquals(422, ex.getStatus());
        verify(itemMapper, never()).insert(any(RouteItineraryItem.class));
    }

    @Test
    @DisplayName("新增项目：未填坐标时继承所关联景点的经纬度")
    void createItemInheritsCoordinatesFromAttraction() {
        when(dayMapper.selectById(11L)).thenReturn(day(11L, 21L, 1, "第一天", null));
        Attraction attraction = new Attraction();
        attraction.id = 5L;
        attraction.name = "大理古城";
        attraction.longitude = new BigDecimal("100.1645720");
        attraction.latitude = new BigDecimal("25.6064850");
        when(attractionMapper.selectById(5L)).thenReturn(attraction);
        when(itemMapper.selectCount(any())).thenReturn(0L);
        when(itemMapper.insert(any(RouteItineraryItem.class))).thenAnswer(invocation -> {
            RouteItineraryItem inserted = invocation.getArgument(0);
            inserted.id = 31L;
            return 1;
        });
        when(itemMapper.selectById(31L)).thenReturn(item(31L, 11L, 1, "ATTRACTION", "大理古城"));

        service.createItem(11L, itemRequest(1, "ATTRACTION", "大理古城", 5L, null, null), ACTOR);

        ArgumentCaptor<RouteItineraryItem> captor = ArgumentCaptor.forClass(RouteItineraryItem.class);
        verify(itemMapper).insert(captor.capture());
        assertEquals(0, captor.getValue().longitude.compareTo(new BigDecimal("100.1645720")));
        assertEquals(0, captor.getValue().latitude.compareTo(new BigDecimal("25.6064850")));
        assertEquals(11L, captor.getValue().dayId, "所属每日行程由 URL 决定");
    }

    @Test
    @DisplayName("新增项目：显式传入坐标时优先使用请求值")
    void createItemPrefersRequestedCoordinates() {
        when(dayMapper.selectById(11L)).thenReturn(day(11L, 21L, 1, "第一天", null));
        when(itemMapper.selectCount(any())).thenReturn(0L);
        when(itemMapper.insert(any(RouteItineraryItem.class))).thenAnswer(invocation -> {
            RouteItineraryItem inserted = invocation.getArgument(0);
            inserted.id = 32L;
            return 1;
        });
        when(itemMapper.selectById(32L)).thenReturn(item(32L, 11L, 1, "OTHER", "自由活动"));

        service.createItem(11L, itemRequest(1, "OTHER", "自由活动", null, 121.4737, 31.2304), ACTOR);

        ArgumentCaptor<RouteItineraryItem> captor = ArgumentCaptor.forClass(RouteItineraryItem.class);
        verify(itemMapper).insert(captor.capture());
        assertEquals(0, captor.getValue().longitude.compareTo(new BigDecimal("121.4737")));
        assertEquals(0, captor.getValue().latitude.compareTo(new BigDecimal("31.2304")));
    }

    @Test
    @DisplayName("修改项目：排序号冲突返回 422；成功时可清空关联景点与坐标")
    void updateItemValidatesAndClearsAttraction() {
        when(itemMapper.selectById(31L)).thenReturn(item(31L, 11L, 1, "ATTRACTION", "大理古城"));
        when(itemMapper.selectCount(any())).thenReturn(1L);

        BusinessException conflict = assertThrows(BusinessException.class,
                () -> service.updateItem(31L, itemRequest(9, "ATTRACTION", "大理古城", null, null, null), ACTOR));
        assertEquals(422, conflict.getStatus());
        verify(itemMapper, never()).update(any(), any());

        when(itemMapper.selectCount(any())).thenReturn(0L);
        service.updateItem(31L, itemRequest(2, "ACTIVITY", "洱海骑行", null, null, null), ACTOR);

        ArgumentCaptor<Wrapper<RouteItineraryItem>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(itemMapper).update(isNull(), captor.capture());
        UpdateWrapper<RouteItineraryItem> wrapper = (UpdateWrapper<RouteItineraryItem>) captor.getValue();
        assertTrue(wrapper.getSqlSet().contains("attraction_id"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(null), "清空关联景点与坐标时写入 NULL");
    }

    @Test
    @DisplayName("删除项目：不存在返回 404，存在则按 id 删除")
    void deleteItemRequiresExistence() {
        when(itemMapper.selectById(404L)).thenReturn(null);
        assertEquals(404, assertThrows(BusinessException.class, () -> service.deleteItem(404L, ACTOR)).getStatus());

        when(itemMapper.selectById(31L)).thenReturn(item(31L, 11L, 1, "MEAL", "午餐"));
        service.deleteItem(31L, ACTOR);
        verify(itemMapper).deleteById(31L);
    }

    // ------------------------------------------------------------------
    // 详情
    // ------------------------------------------------------------------

    @Test
    @DisplayName("详情：返回 route/departures/itinerary/reviews/favorite 完整结构")
    void detailAssemblesWholeContractStructure() {
        TravelRoute route = route(21L, "详情线路", "DRAFT");
        when(routeMapper.selectById(21L)).thenReturn(route);
        Departure departure = departure(21L, LocalDate.now().plusDays(5), 20, 2, 3);
        departure.guideId = null;
        when(departureMapper.selectList(any())).thenReturn(List.of(departure));
        when(dayMapper.selectList(any())).thenReturn(List.of(day(11L, 21L, 1, "第一天", null)));
        when(itemMapper.selectList(any())).thenReturn(List.of());
        when(hotelMapper.selectByIds(any())).thenReturn(List.of());
        when(departureMapper.selectMaps(any())).thenReturn(List.of(priceRow(21L, "1999.00")));
        when(guideMapper.selectByIds(any())).thenReturn(List.of());
        when(orderService.routeReviewsForAdmin(21L)).thenReturn(List.of());

        RouteDetailView detail = service.detail(21L);

        assertEquals(21L, detail.route().id());
        assertEquals(new BigDecimal("1999.00"), detail.route().minAdultPrice());
        assertEquals(1, detail.departures().size());
        assertEquals(15, detail.departures().get(0).availableSeats());
        assertNull(detail.departures().get(0).guideName(), "未分配导游时 guideName 为 null（空联查键回归）");
        assertEquals(1, detail.itinerary().size());
        assertNull(detail.itinerary().get(0).hotelName());
        assertNotNull(detail.reviews());
        assertTrue(!detail.favorite(), "后台详情固定 favorite=false");
    }

    @Test
    @DisplayName("详情：线路不存在返回 404；导游端复用的 routeView 同样校验存在性")
    void detailAndRouteViewRequireExistingRoute() {
        when(routeMapper.selectById(404L)).thenReturn(null);

        assertEquals(404, assertThrows(BusinessException.class, () -> service.detail(404L)).getStatus());
        assertEquals(404, assertThrows(BusinessException.class, () -> service.routeView(404L)).getStatus());
    }

    @Test
    @DisplayName("详情：评价列表原样透出（含被隐藏评价，便于后台管理）")
    void detailKeepsAdminReviewVisibility() {
        when(routeMapper.selectById(21L)).thenReturn(route(21L, "详情线路", "DRAFT"));
        when(departureMapper.selectList(any())).thenReturn(List.of());
        when(departureMapper.selectMaps(any())).thenReturn(List.of());
        when(dayMapper.selectList(any())).thenReturn(List.of());
        ReviewView hidden = new ReviewView(1L, "TA202609070001", 21L, "测试用户", 5, "内容", "HIDDEN",
                LocalDateTime.now());
        when(orderService.routeReviewsForAdmin(21L)).thenReturn(List.of(hidden));

        RouteDetailView detail = service.detail(21L);

        assertEquals(1, detail.reviews().size());
        assertEquals("HIDDEN", detail.reviews().get(0).status());
    }

    // ------------------------------------------------------------------
    // 操作日志（与业务写入同一事务）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("操作日志：创建线路在业务方法内记录，且带操作人与对象主键")
    void createRecordsOperationLogInsideBusinessMethod() {
        when(routeMapper.insert(any(TravelRoute.class))).thenAnswer(invocation -> {
            TravelRoute inserted = invocation.getArgument(0);
            inserted.id = 77L;
            return 1;
        });
        when(routeMapper.selectById(77L)).thenReturn(route(77L, "日志线路", "DRAFT"));
        when(departureMapper.selectMaps(any())).thenReturn(List.of());
        when(departureMapper.selectList(any())).thenReturn(List.of());

        service.create(upsert("日志线路", "上海", "云南", 3), ACTOR);

        verify(operationLog).record(ACTOR, "线路", "CREATE", "ROUTE", 77L, "创建线路：日志线路");
    }

    @Test
    @DisplayName("操作日志：上架、行程与项目的增删改都记录在 service 内（控制器不再负责日志）")
    void everyWriteOperationRecordsItsOwnLog() {
        when(routeMapper.selectById(6L)).thenReturn(route(6L, "可售线路", "DRAFT"), route(6L, "可售线路", "PUBLISHED"));
        when(dayMapper.selectCount(any())).thenReturn(1L);
        when(departureMapper.selectMaps(any())).thenReturn(List.of());
        when(departureMapper.selectList(any())).thenReturn(List.of());
        service.updateStatus(6L, "PUBLISHED", ACTOR);
        verify(operationLog).record(ACTOR, "线路", "STATUS", "ROUTE", 6L, "线路状态变更为 PUBLISHED");

        when(routeMapper.selectById(21L)).thenReturn(route(21L, "草稿线路", "DRAFT"));
        when(dayMapper.selectCount(any())).thenReturn(0L);
        when(dayMapper.insert(any(RouteItineraryDay.class))).thenAnswer(invocation -> {
            RouteItineraryDay inserted = invocation.getArgument(0);
            inserted.id = 11L;
            return 1;
        });
        when(dayMapper.selectById(11L)).thenReturn(day(11L, 21L, 2, "第二天", null));
        service.createDay(21L, new ItineraryDayRequest(2, "第二天", null, null, null, null), ACTOR);
        verify(operationLog).record(ACTOR, "行程", "CREATE", "ITINERARY_DAY", 11L,
                "线路 21 新增第 2 天行程");

        when(itemMapper.selectById(31L)).thenReturn(item(31L, 11L, 1, "MEAL", "午餐"));
        service.deleteItem(31L, ACTOR);
        verify(operationLog).record(ACTOR, "行程", "DELETE", "ITINERARY_ITEM", 31L,
                "删除第 11 天的行程项目：午餐");
    }

    @Test
    @DisplayName("操作日志：幂等请求不产生日志（没有实际写入就不留审计噪音）")
    void idempotentOperationsDoNotLog() {
        when(routeMapper.selectById(7L)).thenReturn(route(7L, "已上架", "PUBLISHED"));
        when(departureMapper.selectMaps(any())).thenReturn(List.of());
        when(departureMapper.selectList(any())).thenReturn(List.of());

        service.updateStatus(7L, "PUBLISHED", ACTOR);

        verify(operationLog, never()).record(any(), any(), any(), any(), any(), any());
    }

    /**
     * 日志写入失败时必须把异常抛出去，让外层事务回滚业务写入；
     * 这正是把日志放进 Service 事务方法（而不是 Controller）的目的。
     */
    @Test
    @DisplayName("操作日志：日志写入失败时异常向上抛出，业务写入随事务回滚")
    void logFailurePropagatesSoBusinessWriteRollsBack() {
        when(routeMapper.insert(any(TravelRoute.class))).thenAnswer(invocation -> {
            TravelRoute inserted = invocation.getArgument(0);
            inserted.id = 88L;
            return 1;
        });
        doThrow(new RuntimeException("operation_log 写入失败"))
                .when(operationLog).record(any(), any(), any(), any(), any(), any());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.create(upsert("日志失败线路", "上海", "云南", 3), ACTOR));

        assertEquals("operation_log 写入失败", ex.getMessage());
        // 业务写入已经发生，但因为与日志同事务，异常会触发整体回滚（集成测试里用真实事务验证）。
        verify(routeMapper).insert(any(TravelRoute.class));
    }

    // ------------------------------------------------------------------
    // 测试数据构造
    // ------------------------------------------------------------------

    private static TravelRoute route(Long id, String name, String status) {
        TravelRoute route = new TravelRoute();
        route.id = id;
        route.name = name;
        route.departureCity = "上海";
        route.destination = "云南";
        route.durationDays = 6;
        route.status = status;
        route.deleted = 0;
        route.ratingAvg = new BigDecimal("4.50");
        route.ratingCount = 10;
        route.validBookingCount = 20;
        return route;
    }

    private static RouteUpsertRequest upsert(String name, String city, String destination, int days) {
        return new RouteUpsertRequest(name, city, destination, days, null, null, null, null, null);
    }

    private static RouteItineraryDay day(Long id, Long routeId, int dayNumber, String title, Long hotelId) {
        RouteItineraryDay day = new RouteItineraryDay();
        day.id = id;
        day.routeId = routeId;
        day.dayNumber = dayNumber;
        day.title = title;
        day.hotelId = hotelId;
        return day;
    }

    private static RouteItineraryItem item(Long id, Long dayId, int sortNo, String type, String name) {
        RouteItineraryItem item = new RouteItineraryItem();
        item.id = id;
        item.dayId = dayId;
        item.sortNo = sortNo;
        item.itemType = type;
        item.name = name;
        return item;
    }

    private static ItineraryItemRequest itemRequest(int sortNo, String type, String name,
                                                    Long attractionId, Double longitude, Double latitude) {
        return new ItineraryItemRequest(sortNo, type, name, null, attractionId, longitude, latitude);
    }

    private static Departure departure(Long routeId, LocalDate startDate, int max, int reserved, int confirmed) {
        Departure departure = new Departure();
        departure.id = 51L;
        departure.routeId = routeId;
        departure.startDate = startDate;
        departure.endDate = startDate.plusDays(5);
        departure.adultPrice = new BigDecimal("2999.00");
        departure.childPrice = new BigDecimal("1999.00");
        departure.maxPeople = max;
        departure.reservedPeople = reserved;
        departure.confirmedPeople = confirmed;
        departure.status = "OPEN";
        departure.guideId = 1L;
        return departure;
    }

    private static Map<String, Object> priceRow(Long routeId, String price) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("routeId", routeId);
        row.put("minAdultPrice", new BigDecimal(price));
        return row;
    }

    private static Page<TravelRoute> pageOf(List<TravelRoute> records, long total) {
        Page<TravelRoute> page = new Page<>(1, 20);
        page.setRecords(new ArrayList<>(records));
        page.setTotal(total);
        return page;
    }

    /** 供 {@link DepartureView} 计算余位时使用的常量断言参考。 */
    @SuppressWarnings("unused")
    private static int availableSeats(int max, int reserved, int confirmed) {
        return Math.max(max - reserved - confirmed, 0);
    }
}
