package com.travelagency.domain.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.audit.OperationLogRecorder;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.DepartureUpsertRequest;
import com.travelagency.domain.dto.DepartureView;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.Guide;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.GuideMapper;
import com.travelagency.domain.mapper.TravelOrderMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 成员 C 团期管理模块：{@link DepartureService#create} / {@link DepartureService#update} /
 * {@link DepartureService#changeStatus} / {@link DepartureService#page} 业务规则单元测试。
 *
 * <p>不启动 Spring、不连接数据库，全部依赖用 Mockito 替身，因此随普通 {@code mvn test} 执行。
 * 覆盖 docs/openapi.yaml 中 Admin Departures 的契约语义与 docs/API.md 第 4 节的请求约束：</p>
 * <ul>
 *   <li>创建团期一律 DRAFT，{@code reservedPeople} / {@code confirmedPeople} / {@code version}
 *       由服务端初始化，不接受客户端输入；</li>
 *   <li>修改团期只写契约允许的可编辑字段，绝不整体写回实体覆盖名额计数与状态；</li>
 *   <li>最大人数不得小于已占用名额（422），避免可用名额被钳到 0 后隐藏超卖；</li>
 *   <li>已产生订单的团期不能改挂线路（409），避免历史订单与其团期指向不同线路；</li>
 *   <li>引用不存在的线路 / 导游属于字段语义错误（422），导游时间重叠属于状态冲突（409）；</li>
 *   <li>状态变更校验契约枚举（422）并返回更新后的视图，同时级联订单状态。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DepartureAdminServiceTest {

    /** 模拟登录后台操作人（Controller 传入 CurrentUser.required().userId()）。 */
    private static final long ACTOR = 99L;

    private static final Long ROUTE_ID = 21L;
    private static final Long DEPARTURE_ID = 51L;
    private static final Long GUIDE_ID = 7L;

    @Mock private DepartureMapper departureMapper;
    @Mock private TravelOrderMapper orderMapper;
    @Mock private TravelRouteMapper routeMapper;
    @Mock private GuideMapper guideMapper;
    @Mock private OperationLogRecorder operationLog;

    private DepartureService service;

    @BeforeEach
    void setUp() {
        service = new DepartureService(departureMapper, orderMapper, routeMapper, guideMapper, operationLog);
    }

    // ------------------------------------------------------------------
    // 创建团期
    // ------------------------------------------------------------------

    @Test
    @DisplayName("创建：状态固定 DRAFT，名额计数与版本由服务端初始化")
    void createForcesServerOwnedFields() {
        stubRoute(ROUTE_ID);
        when(departureMapper.insert(any(Departure.class))).thenAnswer(invocation -> {
            invocation.<Departure>getArgument(0).id = DEPARTURE_ID;
            return 1;
        });
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "DRAFT", 30, 0, 0, null));

        service.create(request(ROUTE_ID, null, 30), ACTOR);

        ArgumentCaptor<Departure> captor = ArgumentCaptor.forClass(Departure.class);
        verify(departureMapper).insert(captor.capture());
        Departure saved = captor.getValue();
        assertEquals("DRAFT", saved.status, "新建团期必须是草稿，先上架才能售卖");
        assertEquals(0, saved.reservedPeople.intValue(), "预留名额必须从 0 起算");
        assertEquals(0, saved.confirmedPeople.intValue(), "确认名额必须从 0 起算");
        assertEquals(0, saved.version.intValue(), "版本号必须由服务端归零");
        assertEquals(ROUTE_ID, saved.routeId);
        assertEquals(30, saved.maxPeople.intValue());
    }

    @Test
    @DisplayName("创建：写入成功后返回契约视图并记录操作日志")
    void createReturnsViewAndRecordsLog() {
        stubRoute(ROUTE_ID);
        stubGuide(GUIDE_ID);
        when(departureMapper.insert(any(Departure.class))).thenAnswer(invocation -> {
            invocation.<Departure>getArgument(0).id = DEPARTURE_ID;
            return 1;
        });
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "DRAFT", 30, 0, 0, GUIDE_ID));

        DepartureView view = service.create(request(ROUTE_ID, GUIDE_ID, 30), ACTOR);

        assertNotNull(view);
        assertEquals(DEPARTURE_ID, view.id());
        assertEquals("云南 6 日", view.routeName(), "视图必须带联查到的线路名");
        assertEquals("李导", view.guideName(), "视图必须带联查到的导游名");
        assertEquals(30, view.availableSeats().intValue());
        verify(operationLog).record(eq(ACTOR), eq("团期"), eq("CREATE"), eq("DEPARTURE"),
                any(), anyString());
    }

    @Test
    @DisplayName("创建：引用不存在的线路返回 422，且不写库")
    void createRejectsUnknownRoute() {
        when(routeMapper.selectById(ROUTE_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(request(ROUTE_ID, null, 30), ACTOR));

        assertEquals(422, ex.getStatus());
        assertEquals("VALIDATION_ERROR", ex.getCode());
        verify(departureMapper, never()).insert(any(Departure.class));
    }

    @Test
    @DisplayName("创建：引用不存在的导游返回 422，且不写库")
    void createRejectsUnknownGuide() {
        stubRoute(ROUTE_ID);
        when(guideMapper.selectById(GUIDE_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(request(ROUTE_ID, GUIDE_ID, 30), ACTOR));

        assertEquals(422, ex.getStatus());
        assertEquals("VALIDATION_ERROR", ex.getCode());
        verify(departureMapper, never()).insert(any(Departure.class));
    }

    @Test
    @DisplayName("创建：返程早于出发返回 422，且不写库")
    void createRejectsInvertedDateRange() {
        LocalDate start = LocalDate.now().plusDays(10);
        DepartureUpsertRequest inverted = new DepartureUpsertRequest(ROUTE_ID, start, start.minusDays(1),
                new BigDecimal("2999.00"), new BigDecimal("1999.00"), 30, null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(inverted, ACTOR));

        assertEquals(422, ex.getStatus());
        verify(departureMapper, never()).insert(any(Departure.class));
    }

    @Test
    @DisplayName("创建：最大人数小于 1 返回 422，且不写库")
    void createRejectsNonPositiveCapacity() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(request(ROUTE_ID, null, 0), ACTOR));

        assertEquals(422, ex.getStatus());
        verify(departureMapper, never()).insert(any(Departure.class));
    }

    @Test
    @DisplayName("创建：价格为负返回 422，且不写库")
    void createRejectsNegativePrice() {
        LocalDate start = LocalDate.now().plusDays(10);
        DepartureUpsertRequest negative = new DepartureUpsertRequest(ROUTE_ID, start, start.plusDays(5),
                new BigDecimal("-1.00"), new BigDecimal("1999.00"), 30, null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(negative, ACTOR));

        assertEquals(422, ex.getStatus());
        verify(departureMapper, never()).insert(any(Departure.class));
    }

    @Test
    @DisplayName("创建：同一导游时间重叠返回 409，且不写库")
    void createRejectsGuideOverlap() {
        stubRoute(ROUTE_ID);
        stubGuide(GUIDE_ID);
        when(departureMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(request(ROUTE_ID, GUIDE_ID, 30), ACTOR));

        assertEquals(409, ex.getStatus());
        assertEquals("DEPARTURE_STATE_CONFLICT", ex.getCode());
        verify(departureMapper, never()).insert(any(Departure.class));
    }

    // ------------------------------------------------------------------
    // 修改团期
    // ------------------------------------------------------------------

    @Test
    @DisplayName("修改：只写契约允许的字段，绝不覆盖名额计数、状态与版本")
    void updateNeverTouchesServerOwnedColumns() {
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 4, 8, null));
        stubRoute(ROUTE_ID);
        when(departureMapper.update(isNull(), any())).thenReturn(1);

        service.update(DEPARTURE_ID, request(ROUTE_ID, null, 40), ACTOR);

        ArgumentCaptor<Wrapper<Departure>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(departureMapper).update(isNull(), captor.capture());
        String sqlSet = ((UpdateWrapper<Departure>) captor.getValue()).getSqlSet();
        assertTrue(sqlSet.contains("max_people"), "可编辑字段应当写入");
        assertTrue(sqlSet.contains("adult_price"), "可编辑字段应当写入");
        // 这一组断言是本用例的核心：整体写回实体会让已报名人数凭空消失（可用名额虚增、进而超卖）。
        assertFalse(sqlSet.contains("reserved_people"), "不得写入 reserved_people");
        assertFalse(sqlSet.contains("confirmed_people"), "不得写入 confirmed_people");
        assertFalse(sqlSet.contains("status"), "不得写入 status");
        assertFalse(sqlSet.contains("version"), "不得写入 version");
    }

    @Test
    @DisplayName("修改：最大人数小于已占用名额返回 422，且不更新")
    void updateRejectsCapacityBelowOccupiedSeats() {
        // 已占用 4 + 8 = 12 人。
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 4, 8, null));
        stubRoute(ROUTE_ID);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(DEPARTURE_ID, request(ROUTE_ID, null, 11), ACTOR));

        assertEquals(422, ex.getStatus());
        assertEquals("VALIDATION_ERROR", ex.getCode());
        verify(departureMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("修改：最大人数恰好等于已占用名额时允许（边界）")
    void updateAllowsCapacityEqualToOccupiedSeats() {
        // 第一次读用于定位与名额校验，第二次读是写入后的回查：替身不会真的改库，
        // 所以这里显式返回"已按新容量落库"的那一行，断言才对应真实运行时的结果。
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 4, 8, null))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 12, 4, 8, null));
        stubRoute(ROUTE_ID);
        when(departureMapper.update(isNull(), any())).thenReturn(1);

        DepartureView view = service.update(DEPARTURE_ID, request(ROUTE_ID, null, 12), ACTOR);

        assertNotNull(view);
        assertEquals(0, view.availableSeats().intValue(), "名额刚好坐满时余位为 0");
        verify(departureMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("修改：已产生订单的团期不能改挂线路（409）")
    void updateRejectsRouteRebindWhenOrdersExist() {
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 0, 0, null));
        Long otherRouteId = 22L;
        stubRoute(otherRouteId);
        when(orderMapper.selectCount(any())).thenReturn(3L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(DEPARTURE_ID, request(otherRouteId, null, 30), ACTOR));

        assertEquals(409, ex.getStatus());
        assertEquals("DEPARTURE_STATE_CONFLICT", ex.getCode());
        verify(departureMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("修改：草稿且无订单时可以改挂线路")
    void updateAllowsRouteRebindWithoutOrders() {
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "DRAFT", 30, 0, 0, null));
        Long otherRouteId = 22L;
        stubRoute(otherRouteId);
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(departureMapper.update(isNull(), any())).thenReturn(1);

        assertNotNull(service.update(DEPARTURE_ID, request(otherRouteId, null, 30), ACTOR));

        verify(operationLog).record(eq(ACTOR), eq("团期"), eq("UPDATE"), eq("DEPARTURE"),
                any(), anyString());
    }

    /**
     * 改挂线路必须限定在尚未开放报名的团期：下单只接受 OPEN，草稿团期不可能产生订单，
     * 于是"查出有没有订单 → 再改线路"这个窗口就不存在了
     * （否则并发下单会把改挂前的旧线路 id 存进订单）。
     */
    @Test
    @DisplayName("修改：已开放报名（非草稿）即使没有订单也不能改挂线路（409）")
    void updateRejectsRouteRebindWhenNotDraft() {
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 0, 0, null));
        Long otherRouteId = 22L;
        stubRoute(otherRouteId);
        when(orderMapper.selectCount(any())).thenReturn(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(DEPARTURE_ID, request(otherRouteId, null, 30), ACTOR));

        assertEquals(409, ex.getStatus());
        assertEquals("DEPARTURE_STATE_CONFLICT", ex.getCode());
        verify(departureMapper, never()).update(any(), any());
    }

    // ------------------------------------------------------------------
    // 并发闸门：判定必须与写入在同一条 UPDATE 里
    // ------------------------------------------------------------------

    /** 名额闸门必须出现在 UPDATE 的 WHERE 里，而不是只存在于应用层的 if。 */
    @Test
    @DisplayName("修改：名额闸门写进 UPDATE 的 WHERE 子句")
    void updatePutsCapacityGateIntoTheStatement() {
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 0, 0, null));
        stubRoute(ROUTE_ID);
        when(departureMapper.update(isNull(), any())).thenReturn(1);

        service.update(DEPARTURE_ID, request(ROUTE_ID, null, 40), ACTOR);

        ArgumentCaptor<Wrapper<Departure>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(departureMapper).update(isNull(), captor.capture());
        String where = ((UpdateWrapper<Departure>) captor.getValue()).getSqlSegment();
        assertTrue(where.contains("reserved_people") && where.contains("confirmed_people"),
                "名额闸门必须在同一条 UPDATE 的 WHERE 中，实际为：" + where);
    }

    /** 改挂时 WHERE 还要带 status = DRAFT，并发上架才能让这条 UPDATE 匹配 0 行。 */
    @Test
    @DisplayName("修改：改挂线路时 UPDATE 追加 status = DRAFT 条件")
    void updatePutsDraftGuardIntoTheStatementWhenRebinding() {
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "DRAFT", 30, 0, 0, null));
        Long otherRouteId = 22L;
        stubRoute(otherRouteId);
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(departureMapper.update(isNull(), any())).thenReturn(1);

        service.update(DEPARTURE_ID, request(otherRouteId, null, 30), ACTOR);

        ArgumentCaptor<Wrapper<Departure>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(departureMapper).update(isNull(), captor.capture());
        String where = ((UpdateWrapper<Departure>) captor.getValue()).getSqlSegment();
        assertTrue(where.contains("status"), "改挂时的 WHERE 必须包含 status 闸门，实际为：" + where);
    }

    /**
     * 读取时显示名额充足、写入时已被并发下单占满：UPDATE 匹配 0 行，
     * 必须判定为 409，而不是当成成功更新（否则会写出已占人数 &gt; 最大人数的团期）。
     *
     * <p>原因必须用当前读 {@code selectByIdForUpdate} 核实 —— REPEATABLE READ 下
     * 普通 {@code selectById} 会读回本事务的旧快照，把并发变化看成"没变化"。</p>
     */
    @Test
    @DisplayName("修改：并发下单占位导致名额闸门失效时返回 409，而不是默默写入")
    void updateReportsCapacityConflictWhenGateFailsInsideTheUpdate() {
        // 第一次读：30 人上限、0 人占用，前置校验通过。
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 0, 0, null));
        // 当前读：并发下单后已占 20 人，而本次要把上限改成 10。
        when(departureMapper.selectByIdForUpdate(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 8, 12, null));
        stubRoute(ROUTE_ID);
        when(departureMapper.update(isNull(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(DEPARTURE_ID, request(ROUTE_ID, null, 10), ACTOR));

        assertEquals(409, ex.getStatus());
        assertEquals("DEPARTURE_CAPACITY_CONFLICT", ex.getCode());
        verify(departureMapper).selectByIdForUpdate(DEPARTURE_ID);
        verify(operationLog, never()).record(any(), anyString(), anyString(), anyString(), any(), anyString());
    }

    /** 并发把团期上架后，改挂线路的 UPDATE 会匹配 0 行，必须判定为 409 而不是成功。 */
    @Test
    @DisplayName("修改：改挂期间团期被并发上架时返回 409")
    void updateReportsConflictWhenConcurrentPublishBlocksRebind() {
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "DRAFT", 30, 0, 0, null));
        when(departureMapper.selectByIdForUpdate(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 0, 0, null));
        Long otherRouteId = 22L;
        stubRoute(otherRouteId);
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(departureMapper.update(isNull(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(DEPARTURE_ID, request(otherRouteId, null, 30), ACTOR));

        assertEquals(409, ex.getStatus());
        assertEquals("DEPARTURE_STATE_CONFLICT", ex.getCode());
    }

    /**
     * 影响行数为 0 一律失败，**没有"其实是成功"的分支**。
     *
     * <p>这里刻意让当前读返回一行"看起来完全满足条件"的数据：这正是 REPEATABLE READ 下
     * 用普通 {@code selectById} 回读时会出现的假象（旧快照里名额没被占）。
     * 条件更新已经确认没生效，就不能凭这次读取放行 —— 否则方法会继续写操作日志并返回成功，
     * 而库里的团期根本没被修改。fail-closed 才是正确方向。</p>
     */
    @Test
    @DisplayName("修改：影响行数为 0 时一律失败，即使当前读看起来满足条件")
    void updateFailsClosedWhenZeroRowsCannotBeExplained() {
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 4, 8, null));
        when(departureMapper.selectByIdForUpdate(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 4, 8, null));
        stubRoute(ROUTE_ID);
        when(departureMapper.update(isNull(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(DEPARTURE_ID, request(ROUTE_ID, null, 30), ACTOR));

        assertEquals(409, ex.getStatus());
        assertEquals("DEPARTURE_STATE_CONFLICT", ex.getCode());
        verify(operationLog, never()).record(any(), anyString(), anyString(), anyString(), any(), anyString());
    }

    /** 影响行数为 0 且行已不存在时按 404 处理，不把并发删除当成成功。 */
    @Test
    @DisplayName("修改：影响行数为 0 且团期已被删除时返回 404")
    void updateReportsNotFoundWhenRowDisappeared() {
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 4, 8, null));
        when(departureMapper.selectByIdForUpdate(DEPARTURE_ID)).thenReturn(null);
        stubRoute(ROUTE_ID);
        when(departureMapper.update(isNull(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(DEPARTURE_ID, request(ROUTE_ID, null, 40), ACTOR));

        assertEquals(404, ex.getStatus());
        assertEquals("RESOURCE_NOT_FOUND", ex.getCode());
    }

    @Test
    @DisplayName("修改：团期不存在返回 404")
    void updateRejectsUnknownDeparture() {
        when(departureMapper.selectById(DEPARTURE_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(DEPARTURE_ID, request(ROUTE_ID, null, 30), ACTOR));

        assertEquals(404, ex.getStatus());
        assertEquals("RESOURCE_NOT_FOUND", ex.getCode());
        verifyNoInteractions(routeMapper);
    }

    @Test
    @DisplayName("修改：同一导游时间重叠返回 409，且不更新")
    void updateRejectsGuideOverlap() {
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 0, 0, null));
        stubRoute(ROUTE_ID);
        stubGuide(GUIDE_ID);
        when(departureMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(DEPARTURE_ID, request(ROUTE_ID, GUIDE_ID, 30), ACTOR));

        assertEquals(409, ex.getStatus());
        verify(departureMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("修改：字段语义错误（日期颠倒）返回 422，且不读线路")
    void updateRejectsInvertedDateRangeBeforeLookups() {
        LocalDate start = LocalDate.now().plusDays(10);
        DepartureUpsertRequest inverted = new DepartureUpsertRequest(ROUTE_ID, start, start.minusDays(1),
                new BigDecimal("2999.00"), new BigDecimal("1999.00"), 30, null);
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 0, 0, null));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(DEPARTURE_ID, inverted, ACTOR));

        assertEquals(422, ex.getStatus());
        verifyNoInteractions(routeMapper);
    }

    // ------------------------------------------------------------------
    // 状态变更
    // ------------------------------------------------------------------

    @Test
    @DisplayName("状态：取值不在契约枚举内返回 422，且不写库")
    void changeStatusRejectsValueOutsideContractEnum() {
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "DRAFT", 30, 0, 0, null));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.changeStatus(DEPARTURE_ID, "NOT_A_STATUS", ACTOR));

        assertEquals(422, ex.getStatus());
        assertEquals("VALIDATION_ERROR", ex.getCode());
        verify(departureMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("状态：团期不存在返回 404")
    void changeStatusRejectsUnknownDeparture() {
        when(departureMapper.selectById(DEPARTURE_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.changeStatus(DEPARTURE_ID, "OPEN", ACTOR));

        assertEquals(404, ex.getStatus());
        assertEquals("RESOURCE_NOT_FOUND", ex.getCode());
    }

    @Test
    @DisplayName("状态：返回更新后的团期视图（此前实现返回 data:null）")
    void changeStatusReturnsUpdatedView() {
        // 第一次读用于定位与校验，第二次读是写入后的回查，两者返回不同状态才说明视图确实来自回查。
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "DRAFT", 30, 0, 0, null))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 0, 0, null));
        stubRoute(ROUTE_ID);
        when(departureMapper.update(isNull(), any())).thenReturn(1);

        DepartureView view = service.changeStatus(DEPARTURE_ID, "OPEN", ACTOR);

        assertNotNull(view, "契约要求返回更新后的团期，不能是 null");
        assertEquals("OPEN", view.status(), "视图必须来自写入后的回查结果");
        verify(operationLog).record(eq(ACTOR), eq("团期"), eq("STATUS"), eq("DEPARTURE"),
                any(), anyString());
    }

    @Test
    @DisplayName("状态：改为行程中时级联把已确认订单置为在途")
    void changeStatusToTravellingCascadesOrders() {
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 0, 0, null));
        stubRoute(ROUTE_ID);
        when(departureMapper.update(isNull(), any())).thenReturn(1);
        when(orderMapper.update(isNull(), any())).thenReturn(2);

        service.changeStatus(DEPARTURE_ID, "TRAVELLING", ACTOR);

        verify(orderMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("状态：状态未变化时不重复记录操作日志")
    void changeStatusToSameValueSkipsLog() {
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 0, 0, null));
        stubRoute(ROUTE_ID);
        when(departureMapper.update(isNull(), any())).thenReturn(1);

        service.changeStatus(DEPARTURE_ID, "OPEN", ACTOR);

        verify(operationLog, never()).record(any(), anyString(), anyString(), anyString(), any(), anyString());
    }

    // ------------------------------------------------------------------
    // 列表与详情
    // ------------------------------------------------------------------

    @Test
    @DisplayName("列表：status 不在契约枚举内时返回 422，且不访问数据库")
    void pageRejectsStatusOutsideContractEnum() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.page(null, null, "NOT_A_STATUS", null, null, 1, 20));

        assertEquals(422, ex.getStatus());
        assertEquals("VALIDATION_ERROR", ex.getCode());
        verifyNoInteractions(departureMapper);
    }

    @Test
    @DisplayName("列表：返回分页信封并带 availableSeats / routeName / guideName")
    void pageMapsDepartureViewFields() {
        Departure record = departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 4, 8, GUIDE_ID);
        when(departureMapper.selectPage(any(), any())).thenReturn(pageOf(List.of(record), 1L));
        TravelRoute route = new TravelRoute();
        route.id = ROUTE_ID;
        route.name = "云南 6 日";
        when(routeMapper.selectByIds(any())).thenReturn(new ArrayList<>(List.of(route)));
        Guide guide = new Guide();
        guide.id = GUIDE_ID;
        guide.name = "李导";
        when(guideMapper.selectByIds(any())).thenReturn(new ArrayList<>(List.of(guide)));

        PageResponse<DepartureView> page = service.page(null, null, "OPEN", null, null, 1, 20);

        assertEquals(1, page.items().size());
        assertEquals(1, page.page());
        assertEquals(20, page.size());
        assertEquals(18, page.items().get(0).availableSeats().intValue(), "30 - 4 - 8 = 18");
        assertEquals("云南 6 日", page.items().get(0).routeName());
        assertEquals("李导", page.items().get(0).guideName());
    }

    @Test
    @DisplayName("列表：status 为空白时不作为筛选条件")
    void pageIgnoresBlankStatus() {
        when(departureMapper.selectPage(any(), any())).thenReturn(pageOf(List.of(), 0L));

        PageResponse<DepartureView> page = service.page(null, null, "   ", null, null, 1, 20);

        assertTrue(page.items().isEmpty());
    }

    @Test
    @DisplayName("详情：团期不存在返回 404；存在时返回带线路名与导游名的视图")
    void detailMapsNamesAndRejectsUnknownId() {
        when(departureMapper.selectById(404L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.detail(404L));
        assertEquals(404, ex.getStatus());

        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 1, 2, GUIDE_ID));
        stubRoute(ROUTE_ID);
        stubGuide(GUIDE_ID);

        DepartureView view = service.detail(DEPARTURE_ID);

        assertEquals("云南 6 日", view.routeName());
        assertEquals("李导", view.guideName());
        assertEquals(27, view.availableSeats().intValue());
    }

    @Test
    @DisplayName("详情：未安排导游与线路联查缺失时不抛 NPE")
    void detailToleratesMissingLookups() {
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "DRAFT", 30, 0, 0, null));
        when(routeMapper.selectById(ROUTE_ID)).thenReturn(null);

        DepartureView view = service.detail(DEPARTURE_ID);

        assertNull(view.routeName(), "线路被删除后联查为空，不能抛异常");
        assertNull(view.guideName(), "未安排导游时导游名为 null");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private void stubRoute(Long routeId) {
        TravelRoute route = new TravelRoute();
        route.id = routeId;
        route.name = "云南 6 日";
        route.deleted = 0;
        when(routeMapper.selectById(routeId)).thenReturn(route);
    }

    private void stubGuide(Long guideId) {
        Guide guide = new Guide();
        guide.id = guideId;
        guide.name = "李导";
        when(guideMapper.selectById(guideId)).thenReturn(guide);
    }

    private static DepartureUpsertRequest request(Long routeId, Long guideId, int maxPeople) {
        LocalDate start = LocalDate.now().plusDays(10);
        return new DepartureUpsertRequest(routeId, start, start.plusDays(5),
                new BigDecimal("2999.00"), new BigDecimal("1999.00"), maxPeople, guideId);
    }

    private static Departure departure(Long id, Long routeId, String status,
                                       int max, int reserved, int confirmed, Long guideId) {
        Departure departure = new Departure();
        departure.id = id;
        departure.routeId = routeId;
        departure.startDate = LocalDate.now().plusDays(10);
        departure.endDate = departure.startDate.plusDays(5);
        departure.adultPrice = new BigDecimal("2999.00");
        departure.childPrice = new BigDecimal("1999.00");
        departure.maxPeople = max;
        departure.reservedPeople = reserved;
        departure.confirmedPeople = confirmed;
        departure.guideId = guideId;
        departure.status = status;
        departure.version = 0;
        return departure;
    }

    private static Page<Departure> pageOf(List<Departure> records, long total) {
        Page<Departure> page = new Page<>(1, 20);
        page.setRecords(new ArrayList<>(records));
        page.setTotal(total);
        return page;
    }
}
