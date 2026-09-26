package com.travelagency.domain.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.audit.OperationLogRecorder;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.AdminDepartureView;
import com.travelagency.domain.dto.DepartureCreateRequest;
import com.travelagency.domain.dto.DepartureUpdateRequest;
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
import org.mockito.InOrder;
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
import static org.mockito.Mockito.inOrder;
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

    /** 测试里团期行的初始版本，修改请求默认按这个版本提交。 */
    private static final int DEFAULT_VERSION = 0;

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

        service.create(createRequest(ROUTE_ID, null, 30), ACTOR);

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

        AdminDepartureView view = service.create(createRequest(ROUTE_ID, GUIDE_ID, 30), ACTOR);

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
                () -> service.create(createRequest(ROUTE_ID, null, 30), ACTOR));

        assertEquals(422, ex.getStatus());
        assertEquals("VALIDATION_ERROR", ex.getCode());
        verify(departureMapper, never()).insert(any(Departure.class));
    }

    @Test
    @DisplayName("创建：引用不存在的导游返回 422，且不写库")
    void createRejectsUnknownGuide() {
        stubRoute(ROUTE_ID);
        when(guideMapper.selectByIdForUpdate(GUIDE_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(createRequest(ROUTE_ID, GUIDE_ID, 30), ACTOR));

        assertEquals(422, ex.getStatus());
        assertEquals("VALIDATION_ERROR", ex.getCode());
        verify(departureMapper, never()).insert(any(Departure.class));
    }

    @Test
    @DisplayName("创建：返程早于出发返回 422，且不写库")
    void createRejectsInvertedDateRange() {
        LocalDate start = LocalDate.now().plusDays(10);
        DepartureCreateRequest inverted = new DepartureCreateRequest(ROUTE_ID, start, start.minusDays(1),
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
                () -> service.create(createRequest(ROUTE_ID, null, 0), ACTOR));

        assertEquals(422, ex.getStatus());
        verify(departureMapper, never()).insert(any(Departure.class));
    }

    @Test
    @DisplayName("创建：价格为负返回 422，且不写库")
    void createRejectsNegativePrice() {
        LocalDate start = LocalDate.now().plusDays(10);
        DepartureCreateRequest negative = new DepartureCreateRequest(ROUTE_ID, start, start.plusDays(5),
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
        stubGuideConflict();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(createRequest(ROUTE_ID, GUIDE_ID, 30), ACTOR));

        assertEquals(409, ex.getStatus());
        assertEquals("DEPARTURE_STATE_CONFLICT", ex.getCode());
        verify(departureMapper, never()).insert(any(Departure.class));
    }

    /**
     * 导游时间冲突是范围重叠判断，MySQL 无法用唯一键表达，只能"先查再写"。
     * 因此必须先锁住导游行、再用<b>当前读</b>查重叠团期：
     * 少了锁，两个并发请求会各自查到"没有冲突"再各自插入，把同一位导游排进两个重叠团期；
     * 用普通查询则会读到本事务的旧快照，在快照建立之后才提交的重叠团期上面失明。
     */
    @Test
    @DisplayName("创建：先锁导游行，再用当前读查重叠团期")
    void createLocksGuideRowBeforeCheckingOverlap() {
        stubRoute(ROUTE_ID);
        stubGuide(GUIDE_ID);
        when(departureMapper.insert(any(Departure.class))).thenAnswer(invocation -> {
            invocation.<Departure>getArgument(0).id = DEPARTURE_ID;
            return 1;
        });
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "DRAFT", 30, 0, 0, GUIDE_ID));

        service.create(createRequest(ROUTE_ID, GUIDE_ID, 30), ACTOR);

        InOrder order = inOrder(guideMapper, departureMapper);
        order.verify(guideMapper).selectByIdForUpdate(GUIDE_ID);
        order.verify(departureMapper).lockOverlappingDepartureIds(
                eq(GUIDE_ID), any(), any(), isNull());
    }

    // ------------------------------------------------------------------
    // 修改团期
    // ------------------------------------------------------------------

    @Test
    @DisplayName("修改：只写契约允许的字段，绝不覆盖名额计数与状态；版本按乐观锁自增")
    void updateNeverTouchesServerOwnedColumns() {
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 4, 8, null));
        stubRoute(ROUTE_ID);
        when(departureMapper.update(isNull(), any())).thenReturn(1);

        // 用一个不会与其它字段撞值的版本号，便于精确断言"写入的是 版本+1"。
        service.update(DEPARTURE_ID, updateRequest(ROUTE_ID, null, 40, 7), ACTOR);

        ArgumentCaptor<Wrapper<Departure>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(departureMapper).update(isNull(), captor.capture());
        UpdateWrapper<Departure> wrapper = (UpdateWrapper<Departure>) captor.getValue();
        String sqlSet = wrapper.getSqlSet();
        assertTrue(sqlSet.contains("max_people"), "可编辑字段应当写入");
        assertTrue(sqlSet.contains("adult_price"), "可编辑字段应当写入");
        // 这一组断言是本用例的核心：整体写回实体会让已报名人数凭空消失（可用名额虚增、进而超卖）。
        assertFalse(sqlSet.contains("reserved_people"), "不得写入 reserved_people");
        assertFalse(sqlSet.contains("confirmed_people"), "不得写入 confirmed_people");
        assertFalse(sqlSet.contains("status"), "不得写入 status");
        // 版本由乐观锁自增，写入的是「提交版本 + 1」，而不是把客户端提交的版本原样写回。
        assertTrue(sqlSet.contains("version"), "版本应当按乐观锁自增");
        assertTrue(wrapper.getParamNameValuePairs().containsValue(8),
                "应当写入 版本+1（7 → 8），实际参数：" + wrapper.getParamNameValuePairs());
        assertFalse(wrapper.getParamNameValuePairs().containsValue(7),
                "不得把客户端提交的版本原样写回");
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

        AdminDepartureView view = service.update(DEPARTURE_ID, request(ROUTE_ID, null, 12), ACTOR);

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
        // 改挂走"先锁团期行、再当前读订单"：订单存在时判定必须看当前读的结果。
        when(departureMapper.selectByIdForUpdate(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "DRAFT", 30, 0, 0, null));
        stubExistingOrders();

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
        when(departureMapper.selectByIdForUpdate(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "DRAFT", 30, 0, 0, null));
        Long otherRouteId = 22L;
        stubRoute(otherRouteId);
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
        when(departureMapper.selectByIdForUpdate(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 0, 0, null));
        Long otherRouteId = 22L;
        stubRoute(otherRouteId);

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

    /** 改挂时 WHERE 还要带 status = DRAFT，作为行锁之外的额外写入条件。 */
    @Test
    @DisplayName("修改：改挂线路时 UPDATE 追加 status = DRAFT 条件")
    void updatePutsDraftGuardIntoTheStatementWhenRebinding() {
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "DRAFT", 30, 0, 0, null));
        when(departureMapper.selectByIdForUpdate(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "DRAFT", 30, 0, 0, null));
        Long otherRouteId = 22L;
        stubRoute(otherRouteId);
        when(departureMapper.update(isNull(), any())).thenReturn(1);

        service.update(DEPARTURE_ID, request(otherRouteId, null, 30), ACTOR);

        ArgumentCaptor<Wrapper<Departure>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(departureMapper).update(isNull(), captor.capture());
        String where = ((UpdateWrapper<Departure>) captor.getValue()).getSqlSegment();
        assertTrue(where.contains("status"), "改挂时的 WHERE 必须包含 status 闸门，实际为：" + where);
    }

    /**
     * 改挂必须先锁团期行，再用<b>当前读</b>查订单 —— 顺序和读法都不能换。
     *
     * <p>只加 {@code status = DRAFT} 的写入条件拦不住「上架 → 下单 → 退回草稿」：
     * 三步之后状态确实又是 DRAFT。用普通查询查订单同样拦不住，因为普通查询读的是
     * 本事务的旧快照，看不到这三步提交的结果。</p>
     */
    @Test
    @DisplayName("修改：改挂前先锁团期行，再用当前读查订单")
    void updateLocksDepartureRowBeforeCurrentReadOfOrders() {
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "DRAFT", 30, 0, 0, null));
        when(departureMapper.selectByIdForUpdate(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "DRAFT", 30, 0, 0, null));
        Long otherRouteId = 22L;
        stubRoute(otherRouteId);
        when(departureMapper.update(isNull(), any())).thenReturn(1);

        service.update(DEPARTURE_ID, request(otherRouteId, null, 30), ACTOR);

        InOrder order = inOrder(departureMapper);
        order.verify(departureMapper).selectByIdForUpdate(DEPARTURE_ID);
        order.verify(departureMapper).lockOrderIdsByDeparture(DEPARTURE_ID);
        order.verify(departureMapper).update(isNull(), any());
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
     * 影响行数为 0 时按当前读判定结果：**当前读与请求不一致就必须失败**。
     *
     * <p>这条守住的是原来的核心缺陷：普通 {@code selectById} 回读在 REPEATABLE READ 下会看到
     * 旧快照（并发占位前的人数），把"条件更新没生效"读成"目标状态已达成"，
     * 于是方法继续写日志并返回成功，而库里的团期根本没被改。</p>
     */
    @Test
    @DisplayName("修改：影响行数为 0 且当前读与请求不一致时返回 409")
    void updateFailsWhenCurrentReadDiffersFromTheRequest() {
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 4, 8, null));
        // 当前读显示该行并不是请求要写入的样子（上限仍是 30，请求要改成 40）。
        when(departureMapper.selectByIdForUpdate(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 4, 8, null));
        stubRoute(ROUTE_ID);
        when(departureMapper.update(isNull(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(DEPARTURE_ID, request(ROUTE_ID, null, 40), ACTOR));

        assertEquals(409, ex.getStatus());
        assertEquals("DEPARTURE_STATE_CONFLICT", ex.getCode());
        verify(operationLog, never()).record(any(), anyString(), anyString(), anyString(), any(), anyString());
    }

    /**
     * 乐观锁：提交的版本与库内不一致就是"基于过期数据提交"，必须 409。
     *
     * <p>对应真实场景 —— 两位工作人员各自打开同一条团期，前一位先保存（版本前进一步），
     * 后一位拿着旧版本提交，不能悄悄覆盖前一位对日期 / 价格 / 导游的改动。</p>
     */
    @Test
    @DisplayName("修改：版本过期返回 409 DEPARTURE_VERSION_CONFLICT，且不写库")
    void updateRejectsStaleVersion() {
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 0, 0, null));
        // 库内版本已经前进到 5，客户端仍按 0 提交。
        Departure latest = departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 0, 0, null);
        latest.version = 5;
        when(departureMapper.selectByIdForUpdate(DEPARTURE_ID)).thenReturn(latest);
        stubRoute(ROUTE_ID);
        when(departureMapper.update(isNull(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(DEPARTURE_ID, updateRequest(ROUTE_ID, null, 30, DEFAULT_VERSION), ACTOR));

        assertEquals(409, ex.getStatus());
        assertEquals("DEPARTURE_VERSION_CONFLICT", ex.getCode());
        verify(operationLog, never()).record(any(), anyString(), anyString(), anyString(), any(), anyString());
    }

    /**
     * 版本判定必须排在名额判定之前。
     *
     * <p>两处都只看字段取值：名额判定看到"上限够用"就会放过，改挂判定也看不出"有人在中间改过"，
     * 只有版本能说明这次提交已经过期。所以顺序反了会给出误导性的原因。</p>
     */
    @Test
    @DisplayName("修改：既版本过期又名额不足时，优先报版本冲突")
    void versionConflictTakesPrecedenceOverCapacity() {
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 0, 0, null));
        Departure latest = departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 8, 12, null);
        latest.version = 7;
        when(departureMapper.selectByIdForUpdate(DEPARTURE_ID)).thenReturn(latest);
        stubRoute(ROUTE_ID);
        when(departureMapper.update(isNull(), any())).thenReturn(0);

        // 请求上限 10 < 已占用 20，同时也过期。
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(DEPARTURE_ID, updateRequest(ROUTE_ID, null, 10, DEFAULT_VERSION), ACTOR));

        assertEquals("DEPARTURE_VERSION_CONFLICT", ex.getCode(), "过期提交应当报版本冲突，而不是名额冲突");
    }

    /** 版本闸门与自增都要写进同一条语句：WHERE 带 version，SET 里 version + 1。 */
    @Test
    @DisplayName("修改：版本闸门写进 WHERE，并在 SET 中自增")
    void updatePutsVersionGateIntoTheStatement() {
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 0, 0, null));
        stubRoute(ROUTE_ID);
        when(departureMapper.update(isNull(), any())).thenReturn(1);

        service.update(DEPARTURE_ID, updateRequest(ROUTE_ID, null, 30, DEFAULT_VERSION), ACTOR);

        ArgumentCaptor<Wrapper<Departure>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(departureMapper).update(isNull(), captor.capture());
        UpdateWrapper<Departure> wrapper = (UpdateWrapper<Departure>) captor.getValue();
        assertTrue(wrapper.getSqlSegment().contains("version"),
                "版本闸门必须在 WHERE 中，实际为：" + wrapper.getSqlSegment());
        assertTrue(wrapper.getSqlSet().contains("version"),
                "版本自增必须在 SET 中，实际为：" + wrapper.getSqlSet());
        assertFalse(wrapper.getSqlSet().contains("reserved_people"),
                "名额计数不得被这次修改写回，实际为：" + wrapper.getSqlSet());
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
        stubGuideConflict();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(DEPARTURE_ID, request(ROUTE_ID, GUIDE_ID, 30), ACTOR));

        assertEquals(409, ex.getStatus());
        verify(departureMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("修改：字段语义错误（日期颠倒）返回 422，且不读线路")
    void updateRejectsInvertedDateRangeBeforeLookups() {
        LocalDate start = LocalDate.now().plusDays(10);
        DepartureUpdateRequest inverted = new DepartureUpdateRequest(ROUTE_ID, start, start.minusDays(1),
                new BigDecimal("2999.00"), new BigDecimal("1999.00"), 30, null, DEFAULT_VERSION);
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
    @DisplayName("状态：取值不在契约枚举内返回 422，且不访问数据库")
    void changeStatusRejectsValueOutsideContractEnum() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.changeStatus(DEPARTURE_ID, "NOT_A_STATUS", ACTOR));

        assertEquals(422, ex.getStatus());
        assertEquals("VALIDATION_ERROR", ex.getCode());
        verifyNoInteractions(departureMapper);
    }

    @Test
    @DisplayName("状态：团期不存在返回 404")
    void changeStatusRejectsUnknownDeparture() {
        when(departureMapper.selectByIdForUpdate(DEPARTURE_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.changeStatus(DEPARTURE_ID, "OPEN", ACTOR));

        assertEquals(404, ex.getStatus());
        assertEquals("RESOURCE_NOT_FOUND", ex.getCode());
    }

    @Test
    @DisplayName("状态：在行锁内判定，返回更新后的团期视图（此前实现返回 data:null）")
    void changeStatusReturnsUpdatedView() {
        // 锁内当前读用于判定与写入，写入后的回查（detail）才是视图来源；两者返回不同状态才说明视图确实来自回查。
        when(departureMapper.selectByIdForUpdate(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "DRAFT", 30, 0, 0, null));
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 0, 0, null));
        // 开放报名的守卫会与库内日期比较，这里把库内日期固定为今天，避免依赖运行时的真实日期。
        when(orderMapper.databaseToday()).thenReturn(LocalDate.now());
        stubRoute(ROUTE_ID);
        when(departureMapper.update(isNull(), any())).thenReturn(1);

        AdminDepartureView view = service.changeStatus(DEPARTURE_ID, "OPEN", ACTOR);

        assertNotNull(view, "契约要求返回更新后的团期，不能是 null");
        assertEquals("OPEN", view.status(), "视图必须来自写入后的回查结果");
        verify(departureMapper).selectByIdForUpdate(DEPARTURE_ID);
        verify(operationLog).record(eq(ACTOR), eq("团期"), eq("STATUS"), eq("DEPARTURE"),
                any(), anyString());
    }

    @Test
    @DisplayName("状态：改为行程中时级联把已确认订单置为在途")
    void changeStatusToTravellingCascadesOrders() {
        when(departureMapper.selectByIdForUpdate(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 0, 0, null));
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "TRAVELLING", 30, 0, 0, null));
        stubRoute(ROUTE_ID);
        when(departureMapper.update(isNull(), any())).thenReturn(1);
        when(orderMapper.update(isNull(), any())).thenReturn(2);

        service.changeStatus(DEPARTURE_ID, "TRAVELLING", ACTOR);

        verify(orderMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("状态：状态未变化时不重复记录操作日志")
    void changeStatusToSameValueSkipsLog() {
        when(departureMapper.selectByIdForUpdate(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 0, 0, null));
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 0, 0, null));
        stubRoute(ROUTE_ID);
        when(departureMapper.update(isNull(), any())).thenReturn(1);

        service.changeStatus(DEPARTURE_ID, "OPEN", ACTOR);

        verify(operationLog, never()).record(any(), anyString(), anyString(), anyString(), any(), anyString());
    }

    /**
     * 终态不可回退：已结束或已取消的团期不能再改成其它状态。
     *
     * <p>否则一条已完成的团期可以重新变成 OPEN 继续售卖，而 {@code confirmedPeople}
     * 还留着历史人数，剩余名额与对外展示都会失真。</p>
     */
    @Test
    @DisplayName("状态：终态（已完成 / 已取消）不能改回其它状态（409）")
    void changeStatusRejectsRevertingFromTerminalState() {
        for (String terminal : List.of("FINISHED", "CANCELLED")) {
            when(departureMapper.selectByIdForUpdate(DEPARTURE_ID))
                    .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, terminal, 30, 8, 12, null));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.changeStatus(DEPARTURE_ID, "OPEN", ACTOR),
                    terminal + " 不应能改回 OPEN");

            assertEquals(409, ex.getStatus());
            assertEquals("DEPARTURE_STATE_CONFLICT", ex.getCode());
        }
        verify(departureMapper, never()).update(any(), any());
    }

    /** 重复提交同一终态是幂等的，不该报冲突。 */
    @Test
    @DisplayName("状态：重复提交同一终态是幂等的")
    void changeStatusAllowsIdempotentSameTerminalState() {
        when(departureMapper.selectByIdForUpdate(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "FINISHED", 30, 0, 0, null));
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "FINISHED", 30, 0, 0, null));
        stubRoute(ROUTE_ID);
        when(departureMapper.update(isNull(), any())).thenReturn(1);

        assertNotNull(service.changeStatus(DEPARTURE_ID, "FINISHED", ACTOR));
    }

    /**
     * 已经出发的团期不能再开放报名。
     *
     * <p>{@code OrderService.create} 只校验状态是 OPEN，因此若允许把出发日期已过的团期打开报名，
     * 就等于把一班已经出发的团重新挂出去卖。</p>
     */
    @Test
    @DisplayName("状态：已过出发日期的团期不能设为 OPEN（409）")
    void changeStatusRejectsOpeningAlreadyDepartedDeparture() {
        Departure departed = departure(DEPARTURE_ID, ROUTE_ID, "DRAFT", 30, 0, 0, null);
        departed.startDate = LocalDate.now().minusDays(3);
        departed.endDate = LocalDate.now().plusDays(2);
        when(departureMapper.selectByIdForUpdate(DEPARTURE_ID)).thenReturn(departed);
        when(orderMapper.databaseToday()).thenReturn(LocalDate.now());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.changeStatus(DEPARTURE_ID, "OPEN", ACTOR));

        assertEquals(409, ex.getStatus());
        assertEquals("DEPARTURE_STATE_CONFLICT", ex.getCode());
        verify(departureMapper, never()).update(any(), any());
    }

    /** 尚未出发的团期正常开放报名，日期闸门不能误伤。 */
    @Test
    @DisplayName("状态：未出发的团期可以设为 OPEN")
    void changeStatusAllowsOpeningUpcomingDeparture() {
        when(departureMapper.selectByIdForUpdate(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "DRAFT", 30, 0, 0, null));
        when(departureMapper.selectById(DEPARTURE_ID))
                .thenReturn(departure(DEPARTURE_ID, ROUTE_ID, "OPEN", 30, 0, 0, null));
        when(orderMapper.databaseToday()).thenReturn(LocalDate.now());
        stubRoute(ROUTE_ID);
        when(departureMapper.update(isNull(), any())).thenReturn(1);

        assertNotNull(service.changeStatus(DEPARTURE_ID, "OPEN", ACTOR));
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

        AdminDepartureView view = service.detail(DEPARTURE_ID);

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

        AdminDepartureView view = service.detail(DEPARTURE_ID);

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
        // 校验存在性走加锁的当前读；视图组装里的 guideName 仍走普通读，两者都要桩。
        when(guideMapper.selectByIdForUpdate(guideId)).thenReturn(guide);
        when(guideMapper.selectById(guideId)).thenReturn(guide);
    }

    /** 让导游时间重叠检查（当前读）报告"已有重叠团期"。 */
    private void stubGuideConflict() {
        when(departureMapper.lockOverlappingDepartureIds(any(), any(), any(), any()))
                .thenReturn(new ArrayList<>(List.of(99L)));
    }

    /** 让改挂前的订单当前读报告"该团期已有订单"。 */
    private void stubExistingOrders() {
        when(departureMapper.lockOrderIdsByDeparture(DEPARTURE_ID))
                .thenReturn(new ArrayList<>(List.of(1L)));
    }

    /** 创建请求（契约上不含 version）。 */
    private static DepartureCreateRequest createRequest(Long routeId, Long guideId, int maxPeople) {
        LocalDate start = LocalDate.now().plusDays(10);
        return new DepartureCreateRequest(routeId, start, start.plusDays(5),
                new BigDecimal("2999.00"), new BigDecimal("1999.00"), maxPeople, guideId);
    }

    /** 修改请求：字段与创建相同，另带读取时的版本号。 */
    private static DepartureUpdateRequest request(Long routeId, Long guideId, int maxPeople) {
        return updateRequest(routeId, guideId, maxPeople, DEFAULT_VERSION);
    }

    private static DepartureUpdateRequest updateRequest(Long routeId, Long guideId, int maxPeople, int version) {
        LocalDate start = LocalDate.now().plusDays(10);
        return new DepartureUpdateRequest(routeId, start, start.plusDays(5),
                new BigDecimal("2999.00"), new BigDecimal("1999.00"), maxPeople, guideId, version);
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
