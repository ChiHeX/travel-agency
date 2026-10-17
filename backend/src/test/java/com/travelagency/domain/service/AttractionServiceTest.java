package com.travelagency.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelagency.common.audit.OperationLogRecorder;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.AttractionDetailView;
import com.travelagency.domain.dto.AttractionUpsertRequest;
import com.travelagency.domain.dto.AttractionView;
import com.travelagency.domain.entity.Attraction;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.AttractionMapper;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.PlaceGuideItemMapper;
import com.travelagency.domain.mapper.RouteItineraryItemMapper;
import com.travelagency.domain.mapper.TravelGuideArticleMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 景点模块 Service 单测：公开详情 + 后台管理（契约 {@code Admin Resources} 的
 * {@code /admin/attractions}）两部分的业务规则与映射。
 *
 * <p>不需要数据库：DB 级往返（201/204/409 的真实落库行为）由
 * {@code AttractionAdminContractIntegrationTest} 在 {@code TRAVEL_MYSQL_TEST=true} 时覆盖。</p>
 */
class AttractionServiceTest {

    private final AttractionMapper attractions = mock(AttractionMapper.class);
    private final RouteItineraryItemMapper itineraryItems = mock(RouteItineraryItemMapper.class);
    private final TravelRouteMapper routes = mock(TravelRouteMapper.class);
    private final DepartureMapper departures = mock(DepartureMapper.class);
    private final PlaceGuideItemMapper placeGuideItems = mock(PlaceGuideItemMapper.class);
    private final TravelGuideArticleMapper guideArticles = mock(TravelGuideArticleMapper.class);
    private final OperationLogRecorder operationLog = mock(OperationLogRecorder.class);
    private final AttractionService service = new AttractionService(attractions, itineraryItems, routes,
            departures, placeGuideItems, guideArticles, operationLog);

    /**
     * MyBatis-Plus 的 {@code update} / {@code deleteById} 返回受影响行数，而 {@code AttractionService}
     * 把 <b>0 行当成"记录已被并发删除"并返回 404</b>。Mockito 对 {@code int} 的默认返回值恰好是 0，
     * 不显式打桩的话所有修改/删除用例都会变成 404；需要验证"0 行"的用例在自己的方法里覆盖这个桩。
     */
    @BeforeEach
    void stubSingleRowWrites() {
        when(attractions.update(ArgumentMatchers.isNull(), any())).thenReturn(1);
        // 必须写 any(Long.class)：BaseMapper 上 deleteById 有 (Serializable) 与 (T) 两个重载，
        // 无类型的 any() 会让编译器无法在两者之间选择。
        when(attractions.deleteById(any(Long.class))).thenReturn(1);
    }

    // ===================== 公开详情 =====================

    @Test
    void hiddenPlaceDoesNotExposeItsTrips() {
        Attraction hidden = new Attraction();
        hidden.id = 3L;
        hidden.status = 0;
        when(attractions.selectById(3L)).thenReturn(hidden);

        assertEquals(404, assertThrows(BusinessException.class, () -> service.detail(3L)).getStatus());
        verify(itineraryItems, never()).publishedRouteIdsForAttraction(3L);
    }

    @Test
    void returnsAllRelatedOpenTripsWithAvailability() {
        Attraction place = new Attraction();
        place.id = 3L;
        place.status = 1;
        TravelRoute route = new TravelRoute();
        route.id = 7L;
        route.name = "大理行程";
        route.departureCity = "昆明";
        Departure first = trip(21L, 7L, 10, 2, 3);
        Departure full = trip(22L, 7L, 10, 4, 6);
        when(attractions.selectById(3L)).thenReturn(place);
        when(itineraryItems.publishedRouteIdsForAttraction(3L)).thenReturn(List.of(7L));
        when(routes.selectBatchIds(List.of(7L))).thenReturn(List.of(route));
        when(departures.selectList(ArgumentMatchers.any())).thenReturn(List.of(first, full));

        AttractionDetailView detail = service.detail(3L);

        assertEquals(List.of(21L, 22L), detail.departures().stream().map(AttractionDetailView.Trip::id).toList());
        assertEquals(List.of(5, 0), detail.departures().stream()
                .map(AttractionDetailView.Trip::availableSeats).toList());
        assertEquals("大理行程", detail.departures().get(0).routeName());
        assertEquals(new BigDecimal("499.00"), detail.departures().get(0).childPrice());
    }

    /**
     * 契约 Attraction.status 是 AccountStatus 枚举，坐标是 JSON number。
     * 实体里的 1 必须变成 "ACTIVE"，BigDecimal 坐标不能被全局序列化器降级成字符串。
     */
    @Test
    @DisplayName("公开详情把实体映射成契约形状：status=ACTIVE、坐标保留 7 位小数")
    void detailMapsEntityToContractShape() {
        Attraction place = place(9L, 1);
        when(attractions.selectById(9L)).thenReturn(place);
        when(itineraryItems.publishedRouteIdsForAttraction(9L)).thenReturn(List.of());

        AttractionView view = service.detail(9L).attraction();

        assertEquals("ACTIVE", view.status());
        // BigDecimal → Double 保留 7 位小数；若不转换，全局序列化器会输出 "100.17" 字符串。
        assertEquals(new BigDecimal("100.1650000").doubleValue(), view.longitude(), 0.0);
        assertEquals(new BigDecimal("25.6940000").doubleValue(), view.latitude(), 0.0);
    }

    // ===================== 后台分页 =====================

    /**
     * 公开列表：只返回已启用景点，且与后台共用同一套契约映射。
     *
     * <p>此前该端点直出实体：{@code status} 是整数 1、坐标是两位小数字符串，
     * 与契约 {@code Attraction} 的 {@code AccountStatus} / JSON number 都不符。</p>
     */
    @Test
    @DisplayName("公开列表：按 status=1 过滤，映射成契约形状（status=ACTIVE、坐标是数字）")
    void pagePublicFiltersEnabledAttractionsAndMapsToContractShape() {
        Page<Attraction> result = new Page<>(1, 20);
        result.setRecords(List.of(place(7L, 1)));
        result.setTotal(1);
        when(attractions.selectPage(any(), any())).thenReturn(result);

        var page = service.pagePublic("大理", "云南", 1, 20);

        ArgumentCaptor<QueryWrapper<Attraction>> query = captor();
        verify(attractions).selectPage(any(), query.capture());
        String sql = query.getValue().getSqlSegment();
        assertTrue(sql.contains("status"), "公开列表必须过滤已启用的景点：" + sql);
        assertTrue(sql.contains("city"), "city 参数应按所属城市过滤：" + sql);
        // name 上没有唯一约束（多个城市都可能有"人民公园"），只按 name 排序时重名行的先后由 MySQL 决定，
        // LIMIT/OFFSET 翻页会出现重复或漏项。这里钉住 id 兜底，与后台列表的 created_at, id 同一思路。
        assertTrue(sql.matches("(?s).*ORDER BY\\s+name\\s+ASC\\s*,\\s*id\\s+ASC.*"),
                "公开列表排序必须带 id 兜底，否则重名景点翻页会重复或漏项：" + sql);
        AttractionView item = page.items().get(0);
        assertEquals("ACTIVE", item.status());
        assertEquals(new BigDecimal("100.1650000").doubleValue(), item.longitude(), 0.0);
    }

    @Test
    @DisplayName("后台列表：keyword 同时匹配名称/简介/城市，页大小钳到契约上限 100")
    void pageFiltersByKeywordAndClampsSize() {
        Attraction matched = place(5L, 1);
        Page<Attraction> result = new Page<>(1, 100);
        result.setRecords(List.of(matched));
        result.setTotal(1);
        when(attractions.selectPage(any(), any())).thenReturn(result);

        var page = service.page("  大理  ", 1, 5000);

        ArgumentCaptor<QueryWrapper<Attraction>> query = captor();
        ArgumentCaptor<Page<Attraction>> requested = pageCaptor();
        verify(attractions).selectPage(requested.capture(), query.capture());
        String sql = query.getValue().getSqlSegment();
        assertTrue(sql.contains("name"), "keyword 应匹配景点名称：" + sql);
        assertTrue(sql.contains("city"), "keyword 应匹配所属城市：" + sql);
        assertTrue(sql.contains("intro"), "keyword 应匹配简介：" + sql);
        assertEquals(100, requested.getValue().getSize(), "size 必须钳到契约上限 100");
        assertEquals("ACTIVE", page.items().get(0).status());
    }

    @Test
    @DisplayName("后台列表：不过滤 status，已停用的景点仍要能被工作人员看到")
    void pageKeepsDisabledAttractionsVisible() {
        Page<Attraction> result = new Page<>(1, 20);
        result.setRecords(List.of(place(6L, 0)));
        result.setTotal(1);
        when(attractions.selectPage(any(), any())).thenReturn(result);

        var page = service.page(null, 1, 20);

        assertEquals(1, page.items().size());
        assertEquals("DISABLED", page.items().get(0).status());
        ArgumentCaptor<QueryWrapper<Attraction>> query = captor();
        verify(attractions).selectPage(any(), query.capture());
        assertFalse(query.getValue().getSqlSegment().contains("status"),
                "后台列表不能按 status 过滤，否则停用的景点无法被改回来");
    }

    // ===================== 后台创建 =====================

    @Test
    @DisplayName("创建：未提交 status 时按 ACTIVE 建档，文本去空白，坐标按 DECIMAL(10,7) 落库")
    void createDefaultsToActiveAndTrimsText() {
        when(attractions.insert(any(Attraction.class))).thenAnswer(invocation -> {
            Attraction inserted = invocation.getArgument(0);
            inserted.id = 31L;
            inserted.createdAt = LocalDateTime.now();
            inserted.updatedAt = inserted.createdAt;
            return 1;
        });
        when(attractions.selectById(31L)).thenAnswer(invocation -> {
            Attraction stored = new Attraction();
            stored.id = 31L;
            stored.name = "大理古城";
            stored.city = "大理";
            stored.dataSource = "团队测试数据";
            stored.longitude = new BigDecimal("100.1650000");
            stored.latitude = new BigDecimal("25.6940000");
            stored.status = 1;
            stored.createdAt = LocalDateTime.now();
            stored.updatedAt = stored.createdAt;
            return stored;
        });

        AttractionView created = service.create(new AttractionUpsertRequest(
                "  大理古城  ", " 大理 ", " 云南省大理市 ", 100.165, 25.694, "简介", " 团队测试数据 ", null), 7L);

        ArgumentCaptor<Attraction> inserted = ArgumentCaptor.forClass(Attraction.class);
        verify(attractions).insert(inserted.capture());
        assertEquals("大理古城", inserted.getValue().name, "名称首尾空白应被去掉");
        assertEquals("大理", inserted.getValue().city);
        assertEquals("团队测试数据", inserted.getValue().dataSource);
        assertEquals(1, inserted.getValue().status, "契约未提交 status 时按 ACTIVE(1) 建档");
        // BigDecimal.valueOf 的标度是 3，库内 DECIMAL(10,7) 落库时补零；这里按数值比较，不约束标度。
        assertEquals(0, new BigDecimal("100.1650000").compareTo(inserted.getValue().longitude));
        // 回查后的响应必须带数据库维护的时间戳，契约把 createdAt/updatedAt 列为必填。
        assertEquals("ACTIVE", created.status());
        assertEquals(31L, created.id());
        verify(operationLog).record(7L, "景点", "CREATE", "ATTRACTION", 31L, "新增景点：大理古城");
    }

    @Test
    @DisplayName("创建：显式提交 DISABLED 时按 0 落库")
    void createHonoursExplicitDisabledStatus() {
        when(attractions.insert(any(Attraction.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Attraction.class).id = 32L;
            return 1;
        });
        when(attractions.selectById(32L)).thenReturn(place(32L, 0));

        AttractionView created = service.create(new AttractionUpsertRequest(
                "停用景点", "大理", null, null, null, null, "团队测试数据", "DISABLED"), 7L);

        ArgumentCaptor<Attraction> inserted = ArgumentCaptor.forClass(Attraction.class);
        verify(attractions).insert(inserted.capture());
        assertEquals(0, inserted.getValue().status);
        assertEquals("DISABLED", created.status());
    }

    // ===================== 后台修改 =====================

    @Test
    @DisplayName("修改：目标不存在返回 404，且不产生任何写入")
    void updateReturnsNotFoundWhenMissing() {
        when(attractions.selectById(404L)).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class, () -> service.update(404L,
                new AttractionUpsertRequest("名称", "城市", null, null, null, null, "来源", null), 7L));

        assertEquals(404, error.getStatus());
        assertEquals("RESOURCE_NOT_FOUND", error.getCode());
        verify(attractions, never()).update(any(), any());
    }

    /**
     * 并发场景：本次编辑读到记录之后、写回之前，另一位管理员把景点停用了。
     *
     * <p>契约里 {@code status} 不是必填，{@code null} 的语义是"保持当前状态"。旧实现的做法是
     * 把读到的旧值一起写回去（{@code SET status = 读到的值}），于是这次普通资料编辑等价于
     * "用旧快照覆盖状态"：读 ACTIVE → 对方停用并提交 → 本事务把 ACTIVE 写回，停用被撤销，
     * 与代码注释声称的"保留库内现值"正好相反。</p>
     *
     * <p>修好之后 {@code status} 未提交时连列名都不进 SET，库内现值由数据库自己保留，
     * 并发的停用不可能被这次编辑覆盖。这里钉住的就是这个机制本身——不写该列，
     * 而不是"写一个恰好正确的值"。</p>
     */
    @Test
    @DisplayName("修改：未提交 status 时该列不进 SET，并发停用的结果不会被覆盖")
    void concurrentDisableSurvivesAnEditThatDoesNotSubmitStatus() {
        Attraction stored = place(41L, 0);
        when(attractions.selectById(41L)).thenReturn(stored);

        service.update(41L, new AttractionUpsertRequest(
                "改名后的景点", "丽江", "新地址", 100.2, 26.8, "新简介", "团队测试数据", null), 7L);

        var wrapper = capturedUpdate();
        assertFalse(wrapper.getSqlSet().contains("status"),
                "status 未提交时不得出现在 SET 里：写回读到的旧值会让并发的停用/启用被这次编辑覆盖");
        assertFalse(wrapper.getParamNameValuePairs().containsValue(0),
                "不得把读到的旧状态当作新值写回");
        assertTrue(wrapper.getSqlSet().contains("data_source"));
        assertTrue(wrapper.getSqlSet().contains("longitude"));
        assertFalse(wrapper.getSqlSet().contains("created_at"), "created_at 不由业务写入");
        assertFalse(wrapper.getSqlSet().contains("updated_at"),
                "显式写回旧 updated_at 会让数据库 ON UPDATE CURRENT_TIMESTAMP 失效");
        assertTrue(wrapper.getParamNameValuePairs().containsValue("改名后的景点"));
        verify(operationLog).record(7L, "景点", "UPDATE", "ATTRACTION", 41L, "修改景点：改名后的景点");
    }

    @Test
    @DisplayName("修改：显式提交 status 时才写该列，并按 ACTIVE(1) 落库")
    void updateAppliesSubmittedStatus() {
        when(attractions.selectById(42L)).thenReturn(place(42L, 0));

        service.update(42L, new AttractionUpsertRequest(
                "重新启用", "大理", null, null, null, null, "团队测试数据", "ACTIVE"), 7L);

        var wrapper = capturedUpdate();
        assertTrue(wrapper.getSqlSet().contains("status"), "显式提交 status 时必须写入该列");
        assertTrue(wrapper.getParamNameValuePairs().containsValue(1));
    }

    @Test
    @DisplayName("修改：显式提交 DISABLED 时按 0 落库")
    void updateAppliesSubmittedDisabledStatus() {
        when(attractions.selectById(45L)).thenReturn(place(45L, 1));

        service.update(45L, new AttractionUpsertRequest(
                "停用景点", "大理", null, null, null, null, "团队测试数据", "DISABLED"), 7L);

        var wrapper = capturedUpdate();
        assertTrue(wrapper.getSqlSet().contains("status"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(0));
    }

    /**
     * 记录在本事务读取之后被并发删除：UPDATE 匹配 0 行。
     *
     * <p>旧实现不检查影响行数，接着照旧记"修改成功"的操作日志，再用同一事务的快照回查
     * （REPEATABLE READ 下仍能看到那一行），最终把一个已经不存在的景点当成"修改后的结果"返回 200。
     * 删除动作本身也被记成成功，事后无从发现这次修改根本没落库。</p>
     */
    @Test
    @DisplayName("修改：记录在写回之前被并发删除（0 行）时返回 404，不记成功日志、不回旧快照")
    void updateFailsWhenTheRowIsDeletedBeforeTheWrite() {
        when(attractions.selectById(44L)).thenReturn(place(44L, 1));
        when(attractions.update(ArgumentMatchers.isNull(), any())).thenReturn(0);

        BusinessException error = assertThrows(BusinessException.class, () -> service.update(44L,
                new AttractionUpsertRequest("景点", "大理", null, null, null, null, "团队测试数据", null), 7L));

        assertEquals(404, error.getStatus());
        assertEquals("RESOURCE_NOT_FOUND", error.getCode());
        verify(operationLog, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("修改：清空可选字段时写入 NULL，而不是保留旧值")
    void updateClearsNullableFields() {
        when(attractions.selectById(43L)).thenReturn(place(43L, 1));

        service.update(43L, new AttractionUpsertRequest(
                "景点", "大理", null, null, null, null, "团队测试数据", null), 7L);

        var wrapper = capturedUpdate();
        assertTrue(wrapper.getParamNameValuePairs().containsValue(null),
                "契约允许 address/longitude/latitude/intro 为 null，PUT 必须能清空它们");
    }

    // ===================== 后台删除 =====================

    @Test
    @DisplayName("删除：目标不存在返回 404")
    void deleteReturnsNotFoundWhenMissing() {
        when(attractions.selectById(404L)).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class, () -> service.delete(404L, 7L));

        assertEquals(404, error.getStatus());
        verify(attractions, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("删除：被线路行程引用返回 409，并且点名是哪个模块挡住的")
    void deleteRejectsAttractionReferencedByItinerary() {
        when(attractions.selectById(51L)).thenReturn(place(51L, 1));
        when(itineraryItems.selectCount(any())).thenReturn(1L);

        BusinessException error = assertThrows(BusinessException.class, () -> service.delete(51L, 7L));

        assertEquals(409, error.getStatus());
        assertEquals("ATTRACTION_STATE_CONFLICT", error.getCode());
        assertTrue(error.getMessage().contains("线路行程"), "409 提示要说明被什么引用：" + error.getMessage());
        verify(attractions, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("删除：被地点指南引用同样返回 409")
    void deleteRejectsAttractionReferencedByPlaceGuide() {
        when(attractions.selectById(52L)).thenReturn(place(52L, 1));
        when(itineraryItems.selectCount(any())).thenReturn(0L);
        when(placeGuideItems.selectCount(any())).thenReturn(2L);

        BusinessException error = assertThrows(BusinessException.class, () -> service.delete(52L, 7L));

        assertEquals(409, error.getStatus());
        assertTrue(error.getMessage().contains("地点指南"), error.getMessage());
        verify(attractions, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("删除：被攻略文章引用同样返回 409")
    void deleteRejectsAttractionReferencedByArticle() {
        when(attractions.selectById(53L)).thenReturn(place(53L, 1));
        when(itineraryItems.selectCount(any())).thenReturn(0L);
        when(placeGuideItems.selectCount(any())).thenReturn(0L);
        when(guideArticles.selectCount(any())).thenReturn(1L);

        BusinessException error = assertThrows(BusinessException.class, () -> service.delete(53L, 7L));

        assertEquals(409, error.getStatus());
        assertTrue(error.getMessage().contains("攻略文章"), error.getMessage());
        verify(attractions, never()).deleteById(any(Long.class));
    }

    /**
     * 记录在本事务读取之后被另一个请求删掉：{@code deleteById} 匹配 0 行。
     *
     * <p>旧实现不看返回值，照样记一条"删除景点：xxx"的操作日志并让接口回 204 ——
     * 别人做的删除被记到这次请求头上，事后从操作日志里分不出真正执行删除的是谁。
     * 现在 0 行按 404 处理，既不记日志也不回成功，调用方看到的结果与
     * "这条景点本来就不存在"完全一致（同 {@link AttractionService#delete} 的顺序路径）。</p>
     */
    @Test
    @DisplayName("删除：记录在删除之前已被并发删除（0 行）时返回 404，不记成功日志")
    void deleteFailsWhenTheRowIsAlreadyGone() {
        when(attractions.selectById(56L)).thenReturn(place(56L, 1));
        when(itineraryItems.selectCount(any())).thenReturn(0L);
        when(placeGuideItems.selectCount(any())).thenReturn(0L);
        when(guideArticles.selectCount(any())).thenReturn(0L);
        when(attractions.deleteById(56L)).thenReturn(0);

        BusinessException error = assertThrows(BusinessException.class, () -> service.delete(56L, 7L));

        assertEquals(404, error.getStatus());
        assertEquals("RESOURCE_NOT_FOUND", error.getCode());
        verify(operationLog, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("删除：未被引用时真正删除并记录操作日志")
    void deleteRemovesUnreferencedAttraction() {
        when(attractions.selectById(54L)).thenReturn(place(54L, 1));
        when(itineraryItems.selectCount(any())).thenReturn(0L);
        when(placeGuideItems.selectCount(any())).thenReturn(0L);
        when(guideArticles.selectCount(any())).thenReturn(0L);

        service.delete(54L, 7L);

        verify(attractions).deleteById(54L);
        verify(operationLog).record(7L, "景点", "DELETE", "ATTRACTION", 54L, "删除景点：景点 54");
    }

    /**
     * 引用检查与删除之间存在窗口：并发插入的引用行只会被外键拦下。
     * 这一层兜底保证调用方拿到的是契约声明的 409，而不是被兜底处理成 500。
     */
    @Test
    @DisplayName("删除：并发插入引用导致外键拒绝时，翻译成 409 而不是 500")
    void deleteTranslatesForeignKeyViolationToConflict() {
        when(attractions.selectById(55L)).thenReturn(place(55L, 1));
        when(itineraryItems.selectCount(any())).thenReturn(0L);
        when(placeGuideItems.selectCount(any())).thenReturn(0L);
        when(guideArticles.selectCount(any())).thenReturn(0L);
        when(attractions.deleteById(55L))
                .thenThrow(new DataIntegrityViolationException("foreign key constraint fails"));

        BusinessException error = assertThrows(BusinessException.class, () -> service.delete(55L, 7L));

        assertEquals(409, error.getStatus());
        assertEquals("ATTRACTION_STATE_CONFLICT", error.getCode());
        verify(operationLog, never()).record(any(), any(), any(), any(), any(), any());
    }

    // ===================== 夹具 =====================

    private Attraction place(Long id, int status) {
        Attraction place = new Attraction();
        place.id = id;
        place.name = "景点 " + id;
        place.city = "大理";
        place.address = "云南省大理市";
        place.longitude = new BigDecimal("100.1650000");
        place.latitude = new BigDecimal("25.6940000");
        place.intro = "演示简介";
        place.dataSource = "团队测试数据";
        place.status = status;
        place.createdAt = LocalDateTime.now();
        place.updatedAt = place.createdAt;
        return place;
    }

    @SuppressWarnings("unchecked")
    private com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Attraction> capturedUpdate() {
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Attraction>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(attractions).update(ArgumentMatchers.isNull(), captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<QueryWrapper<Attraction>> captor() {
        return ArgumentCaptor.forClass(QueryWrapper.class);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Page<Attraction>> pageCaptor() {
        return ArgumentCaptor.forClass(Page.class);
    }

    private static Departure trip(Long id, Long routeId, int capacity, int reserved, int confirmed) {
        Departure departure = new Departure();
        departure.id = id;
        departure.routeId = routeId;
        departure.startDate = LocalDate.now().plusDays(3);
        departure.endDate = LocalDate.now().plusDays(5);
        departure.adultPrice = new BigDecimal("899.00");
        departure.childPrice = new BigDecimal("499.00");
        departure.maxPeople = capacity;
        departure.reservedPeople = reserved;
        departure.confirmedPeople = confirmed;
        return departure;
    }
}
