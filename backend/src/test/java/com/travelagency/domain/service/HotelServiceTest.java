package com.travelagency.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelagency.common.audit.OperationLogRecorder;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.HotelCreateRequest;
import com.travelagency.domain.dto.HotelImageRequest;
import com.travelagency.domain.dto.HotelImageView;
import com.travelagency.domain.dto.HotelUpdateRequest;
import com.travelagency.domain.dto.HotelView;
import com.travelagency.domain.dto.PublicHotelDetailView;
import com.travelagency.domain.entity.Hotel;
import com.travelagency.domain.entity.HotelImage;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.HotelImageMapper;
import com.travelagency.domain.mapper.HotelMapper;
import com.travelagency.domain.mapper.RouteItineraryDayMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HotelServiceTest {

    private final HotelMapper hotels = mock(HotelMapper.class);
    private final HotelImageMapper hotelImages = mock(HotelImageMapper.class);
    private final RouteItineraryDayMapper itineraryDays = mock(RouteItineraryDayMapper.class);
    private final TravelRouteMapper routes = mock(TravelRouteMapper.class);
    private final OperationLogRecorder operationLog = mock(OperationLogRecorder.class);

    private final JsonMapper json = JsonMapper.builder().build();
    private final HotelService service =
            new HotelService(hotels, hotelImages, itineraryDays, routes, operationLog, json);

    /**
     * MyBatis-Plus 的 {@code update} / {@code deleteById} 返回受影响行数，而 {@code HotelService}
     * 把 <b>0 行当成"记录已被并发删除"并返回 404</b>。Mockito 对 {@code int} 的默认返回值恰好是 0，
     * 不显式打桩的话所有修改/删除用例都会变成 404；需要验证"0 行"的用例在自己的方法里覆盖这个桩。
     */
    @BeforeEach
    void stubSingleRowWrites() {
        when(hotels.update(ArgumentMatchers.isNull(), any())).thenReturn(1);
        // 必须写 any(Long.class)：BaseMapper 上 deleteById 有 (Serializable) 与 (T) 两个重载，
        // 无类型的 any() 会让编译器无法在两者之间选择。
        when(hotels.deleteById(any(Long.class))).thenReturn(1);
        when(hotelImages.selectList(any())).thenReturn(List.of());
    }

    // ===================== 后台列表 =====================

    @Test
    @DisplayName("后台列表：keyword 同时匹配名称/地址/简介，页大小钳到契约上限 100，并按创建时间倒序")
    void pageFiltersByKeywordAndClampsSize() {
        Page<Hotel> result = new Page<>(1, 100);
        result.setRecords(List.of(hotel(5L, 1)));
        result.setTotal(1);
        when(hotels.selectPage(any(), any())).thenReturn(result);

        var page = service.page("  昆明  ", null, 0, 5000);

        ArgumentCaptor<QueryWrapper<Hotel>> query = captor();
        ArgumentCaptor<Page<Hotel>> requested = pageCaptor();
        verify(hotels).selectPage(requested.capture(), query.capture());
        String sql = query.getValue().getSqlSegment();
        assertTrue(sql.contains("name"), "keyword 应匹配酒店名称：" + sql);
        assertTrue(sql.contains("address"), "keyword 应匹配酒店地址：" + sql);
        assertTrue(sql.contains("intro"), "keyword 应匹配简介：" + sql);
        // 只按 created_at 排序时，同一秒内批量导入的酒店在翻页时顺序不稳定（会重复或漏项）
        assertTrue(sql.contains("created_at"), "应按创建时间倒序：" + sql);
        assertTrue(sql.contains("id"), "id 必须作为排序的第二关键字，翻页才稳定：" + sql);
        assertEquals(1, requested.getValue().getCurrent(), "page 小于 1 时按第 1 页处理");
        assertEquals(100, requested.getValue().getSize(), "size 必须钳到契约上限 100");
        assertEquals("ACTIVE", page.items().get(0).status());
    }

    @Test
    @DisplayName("后台列表：city 按精确值筛选，未提交时不加该条件")
    void pageFiltersByCityExactly() {
        Page<Hotel> result = new Page<>(1, 20);
        result.setRecords(List.of(hotel(7L, 1)));
        when(hotels.selectPage(any(), any())).thenReturn(result);

        service.page(null, "  杭州  ", 1, 20);

        ArgumentCaptor<QueryWrapper<Hotel>> query = captor();
        verify(hotels).selectPage(any(), query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("city"), "应按 city 过滤：" + query.getValue().getSqlSegment());
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("杭州"),
                "city 应去掉首尾空白后按精确值匹配");
    }

    @Test
    @DisplayName("后台列表：不过滤 status，已停用的酒店仍要能被工作人员看到")
    void pageKeepsDisabledHotelsVisible() {
        Page<Hotel> result = new Page<>(1, 20);
        result.setRecords(List.of(hotel(6L, 0)));
        result.setTotal(1);
        when(hotels.selectPage(any(), any())).thenReturn(result);

        var page = service.page(null, null, 1, 20);

        assertEquals(1, page.items().size());
        assertEquals("DISABLED", page.items().get(0).status());
        ArgumentCaptor<QueryWrapper<Hotel>> query = captor();
        verify(hotels).selectPage(any(), query.capture());
        assertFalse(query.getValue().getSqlSegment().contains("status"),
                "后台列表不能按 status 过滤，否则停用的酒店无法被改回来");
    }

    /**
     * 契约 {@code Hotel} 的 {@code status} 是 {@code AccountStatus} 枚举、坐标是 JSON number。
     * 实体里的 1 必须变成 "ACTIVE"，{@code BigDecimal} 坐标不能被全局序列化器降级成两位小数字符串
     * （直出实体时 {@code JacksonConfig} 会把 102.8320000 写成 "102.83"）。
     */
    @Test
    @DisplayName("列表把实体映射成契约形状：status=ACTIVE、坐标保留 7 位小数、设施/图片带出来")
    void pageMapsEntityToContractShape() {
        Page<Hotel> result = new Page<>(1, 20);
        result.setRecords(List.of(hotel(9L, 1)));
        result.setTotal(1);
        when(hotels.selectPage(any(), any())).thenReturn(result);
        when(hotelImages.selectList(any())).thenReturn(List.of(image(9L, "https://example.com/a.jpg", 1)));

        HotelView item = service.page(null, null, 1, 20).items().get(0);

        assertEquals("ACTIVE", item.status());
        assertEquals("酒店 9", item.name());
        assertEquals("大理", item.city());
        assertEquals("087112345678", item.contactPhone());
        assertEquals(new BigDecimal("102.8320000").doubleValue(), item.longitude(), 0.0);
        assertEquals(new BigDecimal("24.8800000").doubleValue(), item.latitude(), 0.0);
        assertEquals(List.of("WIFI"), item.facilities(), "库内 JSON 文本应还原成字符串数组");
        assertEquals(1, item.images().size());
        assertEquals("https://example.com/a.jpg", item.images().get(0).url());
    }

    @Test
    @DisplayName("后台列表：图片按本页酒店批量查询一次，不做 N+1")
    void pageLoadsImagesForTheWholePageAtOnce() {
        Page<Hotel> result = new Page<>(1, 20);
        result.setRecords(List.of(hotel(71L, 1), hotel(72L, 1)));
        result.setTotal(2);
        when(hotels.selectPage(any(), any())).thenReturn(result);

        service.page(null, null, 1, 20);

        ArgumentCaptor<QueryWrapper<HotelImage>> query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(hotelImages, times(1)).selectList(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("hotel_id"),
                "应按本页酒店主键批量取图：" + query.getValue().getSqlSegment());
        assertTrue(query.getValue().getSqlSegment().contains("sort_order"),
                "排序必须落在 SQL 里，返回顺序才稳定：" + query.getValue().getSqlSegment());
    }

    // ===================== 后台创建 =====================

    @Test
    @DisplayName("创建：未提交 status 时按 ACTIVE 建档，文本去空白，坐标按 DECIMAL(10,7) 落库")
    void createDefaultsToActiveAndTrimsText() {
        when(hotels.insert(any(Hotel.class))).thenAnswer(invocation -> {
            Hotel inserted = invocation.getArgument(0);
            inserted.id = 31L;
            return 1;
        });
        when(hotels.selectOne(any())).thenAnswer(invocation -> {
            Hotel stored = hotel(31L, 1);
            stored.name = "大理演示酒店";
            stored.contactPhone = "0872-1234567";
            return stored;
        });

        HotelView created = service.create(new HotelCreateRequest(
                "  大理演示酒店  ", "  大理  ", " 云南省大理市 ", " 0872-1234567 ",
                " https://example.com/cover.jpg ", null, 4, List.of("WIFI", "PARKING"),
                " 14:00 ", " 12:00 ", 100.165, 25.694,
                "简介", " 团队测试数据 ", null), 7L);

        ArgumentCaptor<Hotel> inserted = ArgumentCaptor.forClass(Hotel.class);
        verify(hotels).insert(inserted.capture());
        assertEquals("大理演示酒店", inserted.getValue().name, "名称首尾空白应被去掉");
        assertEquals("大理", inserted.getValue().city, "城市首尾空白应被去掉");
        assertEquals("云南省大理市", inserted.getValue().address);
        assertEquals("0872-1234567", inserted.getValue().contactPhone);
        assertEquals("https://example.com/cover.jpg", inserted.getValue().coverUrl);
        assertEquals(4, inserted.getValue().starRating);
        assertEquals("14:00", inserted.getValue().checkInTime);
        assertEquals("12:00", inserted.getValue().checkOutTime);
        assertEquals("[\"WIFI\",\"PARKING\"]", inserted.getValue().facilities);
        assertEquals("团队测试数据", inserted.getValue().dataSource);
        assertEquals(1, inserted.getValue().status, "契约未提交 status 时按 ACTIVE(1) 建档");
        // BigDecimal.valueOf 的标度是 3，库内 DECIMAL(10,7) 落库时补零；这里按数值比较，不约束标度。
        assertEquals(0, new BigDecimal("100.1650000").compareTo(inserted.getValue().longitude));
        // 回查后的响应必须带数据库维护的时间戳，契约把 createdAt/updatedAt 列为必填。
        assertEquals("ACTIVE", created.status());
        assertEquals(31L, created.id());
        verify(operationLog).record(7L, "酒店", "CREATE", "HOTEL", 31L, "新增酒店资料：大理演示酒店");
    }

    @Test
    @DisplayName("创建：显式提交 DISABLED 时按 0 落库")
    void createHonoursExplicitDisabledStatus() {
        when(hotels.insert(any(Hotel.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Hotel.class).id = 32L;
            return 1;
        });
        when(hotels.selectOne(any())).thenReturn(hotel(32L, 0));

        HotelView created = service.create(new HotelCreateRequest(
                "停用酒店", "大理", null, null, null, null, null, null, null, null,
                null, null, null, "团队测试数据", "DISABLED"), 7L);

        ArgumentCaptor<Hotel> inserted = ArgumentCaptor.forClass(Hotel.class);
        verify(hotels).insert(inserted.capture());
        assertEquals(0, inserted.getValue().status);
        assertEquals("DISABLED", created.status());
    }

    @Test
    @DisplayName("创建：图片按提交顺序逐条写入，保留 sortOrder")
    void createInsertsImagesInSubmittedOrder() {
        when(hotels.insert(any(Hotel.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Hotel.class).id = 33L;
            return 1;
        });
        when(hotels.selectOne(any())).thenReturn(hotel(33L, 1));

        service.create(new HotelCreateRequest(
                "有图酒店", "杭州", null, null, null,
                List.of(new HotelImageRequest("https://example.com/3.jpg", "外景", 3),
                        new HotelImageRequest("https://example.com/1.jpg", null, 1),
                        new HotelImageRequest("https://example.com/2.jpg", "客房", 2)),
                null, null, null, null, null, null, null, "团队测试数据", null), 7L);

        ArgumentCaptor<HotelImage> inserted = ArgumentCaptor.forClass(HotelImage.class);
        verify(hotelImages, times(3)).insert(inserted.capture());
        assertEquals(List.of("https://example.com/3.jpg", "https://example.com/1.jpg",
                        "https://example.com/2.jpg"),
                inserted.getAllValues().stream().map(image -> image.url).toList(),
                "写入顺序必须与提交顺序一致");
        assertEquals(List.of(3, 1, 2), inserted.getAllValues().stream().map(image -> image.sortOrder).toList());
        assertEquals(33L, inserted.getAllValues().get(0).hotelId);
        assertEquals("外景", inserted.getAllValues().get(0).alt);
    }

    @Test
    @DisplayName("创建：设施标签重复时按 422 拒绝，且不写库")
    void createRejectsDuplicateFacilities() {
        BusinessException error = assertThrows(BusinessException.class, () -> service.create(
                new HotelCreateRequest("重复设施酒店", "杭州", null, null, null, null, null,
                        List.of("WIFI", "WIFI"), null, null, null, null, null, "团队测试数据", null),
                7L));

        assertEquals(422, error.getStatus());
        assertEquals("VALIDATION_ERROR", error.getCode());
        verify(hotels, never()).insert(any(Hotel.class));
    }

    // ===================== 后台修改 =====================

    @Test
    @DisplayName("修改：目标不存在返回 404，且不产生任何写入")
    void updateReturnsNotFoundWhenMissing() {
        when(hotels.selectOne(any())).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class, () -> service.update(404L,
                new HotelUpdateRequest("名称", "大理", null, null, null, null, null, null, null, null,
                        null, null, null, "来源", null, 0), 7L));

        assertEquals(404, error.getStatus());
        assertEquals("RESOURCE_NOT_FOUND", error.getCode());
        verify(hotels, never()).update(any(), any());
    }

    /**
     * 并发场景：本次编辑读到记录之后、写回之前，另一位管理员把酒店停用了。
     *
     * <p>契约里 {@code status} 不是必填，{@code null} 的语义是"保持当前状态"。把读到的旧值
     * 一起写回去（{@code SET status = 读到的值}）会让这次普通资料编辑等价于"用旧快照覆盖状态"：
     * 读 ACTIVE → 对方停用并提交 → 本事务把 ACTIVE 写回，停用被撤销，
     * 与代码注释声称的"保留库内现值"正好相反。</p>
     *
     * <p>修好之后 {@code status} 未提交时连列名都不进 SET，库内现值由数据库自己保留。
     * 这里钉住的就是这个机制本身——不写该列，而不是"写一个恰好正确的值"。</p>
     */
    @Test
    @DisplayName("修改：未提交 status 时该列不进 SET，并发停用的结果不会被覆盖")
    void concurrentDisableSurvivesAnEditThatDoesNotSubmitStatus() {
        when(hotels.selectOne(any())).thenReturn(hotel(41L, 0));

        service.update(41L, new HotelUpdateRequest(
                "改名后的酒店", "昆明", "新地址", "0872-0000000", null, null, null, null, null, null,
                100.2, 26.8, "新简介", "团队测试数据", null, 0), 7L);

        UpdateWrapper<Hotel> wrapper = capturedUpdate();
        assertFalse(wrapper.getSqlSet().contains("status"),
                "status 未提交时不得出现在 SET 里：写回读到的旧值会让并发的停用/启用被这次编辑覆盖");
        assertFalse(wrapper.getParamNameValuePairs().containsValue(0),
                "不得把读到的旧状态当作新值写回");
        assertTrue(wrapper.getSqlSet().contains("data_source"));
        assertTrue(wrapper.getSqlSet().contains("contact_phone"));
        assertTrue(wrapper.getSqlSet().contains("city"));
        assertTrue(wrapper.getSqlSet().contains("longitude"));
        assertFalse(wrapper.getSqlSet().contains("created_at"), "created_at 不由业务写入");
        assertFalse(wrapper.getSqlSet().contains("updated_at"),
                "显式写回旧 updated_at 会让数据库 ON UPDATE CURRENT_TIMESTAMP 失效");
        assertTrue(wrapper.getParamNameValuePairs().containsValue("改名后的酒店"));
        verify(operationLog).record(7L, "酒店", "UPDATE", "HOTEL", 41L, "修改酒店资料：改名后的酒店");
    }

    @Test
    @DisplayName("修改：显式提交 status 时才写该列，并按 ACTIVE(1) 落库")
    void updateAppliesSubmittedStatus() {
        when(hotels.selectOne(any())).thenReturn(hotel(42L, 0));

        service.update(42L, new HotelUpdateRequest(
                "重新启用", "大理", null, null, null, null, null, null, null, null,
                null, null, null, "团队测试数据", "ACTIVE", 0), 7L);

        UpdateWrapper<Hotel> wrapper = capturedUpdate();
        assertTrue(wrapper.getSqlSet().contains("status"), "显式提交 status 时必须写入该列");
        assertTrue(wrapper.getParamNameValuePairs().containsValue(1));
    }

    @Test
    @DisplayName("修改：显式提交 DISABLED 时按 0 落库")
    void updateAppliesSubmittedDisabledStatus() {
        when(hotels.selectOne(any())).thenReturn(hotel(45L, 1));

        service.update(45L, new HotelUpdateRequest(
                "停用酒店", "大理", null, null, null, null, null, null, null, null,
                null, null, null, "团队测试数据", "DISABLED", 0), 7L);

        UpdateWrapper<Hotel> wrapper = capturedUpdate();
        assertTrue(wrapper.getSqlSet().contains("status"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(0));
    }

    /**
     * 记录在本事务读取之后被并发删除：UPDATE 匹配 0 行。
     *
     * <p>旧实现不检查影响行数，接着照旧记"修改成功"的操作日志，再用同一事务的快照回查
     * （REPEATABLE READ 下仍能看到那一行），最终把一家已经不存在的酒店当成"修改后的结果"返回 200。</p>
     */
    @Test
    @DisplayName("修改：记录在写回之前被并发删除（0 行）时返回 404，不记成功日志")
    void updateFailsWhenTheRowIsDeletedBeforeTheWrite() {
        // 第一次读拿到行，UPDATE 之后的重读已经取不到了（记录被并发删除）。
        when(hotels.selectOne(any())).thenReturn(hotel(44L, 1), null);
        when(hotels.update(ArgumentMatchers.isNull(), any())).thenReturn(0);

        BusinessException error = assertThrows(BusinessException.class, () -> service.update(44L,
                new HotelUpdateRequest("酒店", "大理", null, null, null, null, null, null, null, null,
                        null, null, null, "团队测试数据", null, 0), 7L));

        assertEquals(404, error.getStatus());
        assertEquals("RESOURCE_NOT_FOUND", error.getCode());
        verify(operationLog, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("修改：清空可选字段时写入 NULL，而不是保留旧值")
    void updateClearsNullableFields() {
        when(hotels.selectOne(any())).thenReturn(hotel(43L, 1));

        service.update(43L, new HotelUpdateRequest(
                "酒店", "大理", null, null, null, null, null, null, null, null,
                null, null, null, "团队测试数据", null, 0), 7L);

        UpdateWrapper<Hotel> wrapper = capturedUpdate();
        assertTrue(wrapper.getParamNameValuePairs().containsValue(null),
                "契约允许 address/contactPhone/coverUrl/starRating/checkInTime/checkOutTime/经纬度/简介为 null，"
                        + "PUT 必须能清空它们，否则「删掉一段旧资料」没有明确写法");
    }

    @Test
    @DisplayName("修改：图片整体替换（先删后插），提交空集合即清空")
    void updateReplacesImagesEntirely() {
        when(hotels.selectOne(any())).thenReturn(hotel(48L, 1));

        service.update(48L, new HotelUpdateRequest(
                "有图酒店", "杭州", null, null, null,
                List.of(new HotelImageRequest("https://example.com/only.jpg", null, 1)),
                null, null, null, null, null, null, null, "团队测试数据", null, 0), 7L);

        ArgumentCaptor<QueryWrapper<HotelImage>> deleted = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(hotelImages).delete(deleted.capture());
        assertTrue(deleted.getValue().getSqlSegment().contains("hotel_id"),
                "必须先删除该酒店的全部图片记录：" + deleted.getValue().getSqlSegment());
        ArgumentCaptor<HotelImage> inserted = ArgumentCaptor.forClass(HotelImage.class);
        verify(hotelImages, times(1)).insert(inserted.capture());
        assertEquals("https://example.com/only.jpg", inserted.getValue().url);

        service.update(48L, new HotelUpdateRequest(
                "有图酒店", "杭州", null, null, null, List.of(), null, null, null, null,
                null, null, null, "团队测试数据", null, 0), 7L);
        verify(hotelImages, times(2)).delete(any());
        verify(hotelImages, times(1)).insert(any(HotelImage.class));
    }

    /**
     * 酒店资料没有独立的状态端点（契约里状态只能随 PUT 提交），因此"谁把这家酒店停用了"
     * 只能靠操作日志追溯。只记一条笼统的"修改酒店资料"会让停用和改个电话在日志里长得一样。
     * 这里钉住：状态确实变化时要另记一条 {@code STATUS}，口径与团期/线路的状态变更一致。
     */
    @Test
    @DisplayName("修改：状态确实变化时另记一条 STATUS 追溯日志")
    void updateLogsStatusTransitionWhenStatusActuallyChanges() {
        when(hotels.selectOne(any())).thenReturn(hotel(46L, 1));

        service.update(46L, new HotelUpdateRequest(
                "停用酒店", "大理", null, null, null, null, null, null, null, null,
                null, null, null, "团队测试数据", "DISABLED", 0), 7L);

        verify(operationLog).record(7L, "酒店", "UPDATE", "HOTEL", 46L, "修改酒店资料：停用酒店");
        verify(operationLog).record(7L, "酒店", "STATUS", "HOTEL", 46L,
                "酒店状态由 ACTIVE 变更为 DISABLED");
    }

    /** 编辑资料时把状态原样提交回来（表单回填后保存）不算状态变更，不能在日志里刷出噪声。 */
    @Test
    @DisplayName("修改：重复提交与库内相同的状态时不记 STATUS 日志")
    void updateDoesNotLogStatusWhenTheStatusIsUnchanged() {
        when(hotels.selectOne(any())).thenReturn(hotel(47L, 1));

        service.update(47L, new HotelUpdateRequest(
                "状态未变酒店", "大理", null, null, null, null, null, null, null, null,
                null, null, null, "团队测试数据", "ACTIVE", 0), 7L);

        // 日志里的名称是本次提交的名称（applyEditableFields 先写入实体再记录）
        verify(operationLog).record(7L, "酒店", "UPDATE", "HOTEL", 47L, "修改酒店资料：状态未变酒店");
        verify(operationLog, never()).record(any(), any(), ArgumentMatchers.eq("STATUS"),
                any(), any(), any());
    }

    // ===================== 乐观锁 =====================

    /**
     * 详情端点：乐观锁冲突后前端按主键取服务器最新资料，因此它必须回契约形状
     * （含最新 {@code version}，坐标是 number、状态是枚举），不存在时 404。
     */
    @Test
    @DisplayName("详情：按主键返回契约形状（含最新版本号与图片），不存在返回 404")
    void getReturnsContractShapeAndRequiresExistence() {
        Hotel stored = hotel(60L, 1);
        stored.version = 5;
        when(hotels.selectById(60L)).thenReturn(stored);
        when(hotelImages.selectList(any())).thenReturn(List.of(
                image(60L, "https://example.com/1.jpg", 1),
                image(60L, "https://example.com/2.jpg", 2)));

        HotelView view = service.get(60L);

        assertEquals(60L, view.id());
        assertEquals("ACTIVE", view.status());
        assertEquals(5, view.version(), "详情必须给出最新版本号，冲突面板才能用它重新提交");
        assertEquals("酒店 60", view.name());
        assertEquals(2, view.images().size(), "修改表单要能回填已有图片");

        when(hotels.selectById(404L)).thenReturn(null);
        BusinessException error = assertThrows(BusinessException.class, () -> service.get(404L));
        assertEquals(404, error.getStatus());
        assertEquals("RESOURCE_NOT_FOUND", error.getCode());
    }

    @Test
    @DisplayName("修改：提交过期版本返回 409 HOTEL_VERSION_CONFLICT，且不写库、不换图、不记日志")
    void updateRejectsStaleVersion() {
        Hotel stored = hotel(48L, 1);
        stored.version = 2;
        when(hotels.selectOne(any())).thenReturn(stored);

        BusinessException error = assertThrows(BusinessException.class, () -> service.update(48L,
                new HotelUpdateRequest("改名", "大理", null, null, null,
                        List.of(new HotelImageRequest("https://example.com/x.jpg", null, 1)),
                        null, null, null, null, null, null, null, "团队测试数据", null, 1), 7L));

        assertEquals(409, error.getStatus());
        assertEquals("HOTEL_VERSION_CONFLICT", error.getCode());
        assertTrue(error.getMessage().contains("当前版本 2") && error.getMessage().contains("你提交的是 1"),
                "冲突提示要给出双方版本号，运营才知道应当重新载入：" + error.getMessage());
        verify(hotels, never()).update(any(), any());
        verify(hotelImages, never()).delete(any());
        verify(hotelImages, never()).insert(any(HotelImage.class));
        verify(operationLog, never()).record(any(), any(), any(), any(), any(), any());
    }

    /** 版本一致时正常写入：WHERE 带版本条件，SET 把版本推进 1。 */
    @Test
    @DisplayName("修改：版本一致时 WHERE 带版本条件、SET 推进版本号")
    void updateAdvancesVersionWhenTheSubmittedVersionMatches() {
        Hotel stored = hotel(49L, 1);
        stored.version = 4;
        when(hotels.selectOne(any())).thenReturn(stored);

        service.update(49L, new HotelUpdateRequest(
                "改名", "大理", null, null, null, null, null, null, null, null,
                null, null, null, "团队测试数据", null, 4), 7L);

        UpdateWrapper<Hotel> wrapper = capturedUpdate();
        assertTrue(wrapper.getSqlSet().contains("version"), "SET 必须推进版本号：" + wrapper.getSqlSet());
        assertTrue(wrapper.getParamNameValuePairs().containsValue(5), "版本应写回 提交版本 + 1");
        assertTrue(wrapper.getSqlSegment().contains("version"),
                "WHERE 必须带版本条件作为第二道防线：" + wrapper.getSqlSegment());
        assertTrue(wrapper.getParamNameValuePairs().containsValue(4), "WHERE 用提交的版本匹配");
    }

    /**
     * 兜底：UPDATE 影响 0 行、但行仍在且版本已变时，必须报 409 版本冲突而不是 404 ——
     * 把冲突说成"已不存在"会让调用方以为资料被删了，转而重新建一条重复资料。
     */
    @Test
    @DisplayName("修改：0 行且行仍在时按版本冲突处理，不误报 404")
    void updateReportsVersionConflictWhenNoRowMatchesButTheRowExists() {
        Hotel stored = hotel(50L, 1);
        stored.version = 0;
        Hotel changed = hotel(50L, 1);
        changed.version = 7;
        when(hotels.selectOne(any())).thenReturn(stored, changed);
        when(hotels.update(ArgumentMatchers.isNull(), any())).thenReturn(0);

        BusinessException error = assertThrows(BusinessException.class, () -> service.update(50L,
                new HotelUpdateRequest("改名", "大理", null, null, null, null, null, null, null, null,
                        null, null, null, "团队测试数据", null, 0), 7L));

        assertEquals(409, error.getStatus());
        assertEquals("HOTEL_VERSION_CONFLICT", error.getCode());
    }

    // ===================== 后台删除 =====================

    @Test
    @DisplayName("删除：目标不存在返回 404")
    void deleteReturnsNotFoundWhenMissing() {
        when(hotels.selectOne(any())).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class, () -> service.delete(404L, 7L));

        assertEquals(404, error.getStatus());
        verify(hotels, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("删除：被线路行程引用返回 409，并且点名是被行程挡住的")
    void deleteRejectsHotelReferencedByItineraryDay() {
        when(hotels.selectOne(any())).thenReturn(hotel(51L, 1));
        when(itineraryDays.selectCount(any())).thenReturn(1L);

        BusinessException error = assertThrows(BusinessException.class, () -> service.delete(51L, 7L));

        assertEquals(409, error.getStatus());
        assertEquals("HOTEL_STATE_CONFLICT", error.getCode());
        assertTrue(error.getMessage().contains("线路行程"), "409 提示要说明被什么引用：" + error.getMessage());
        verify(hotels, never()).deleteById(any(Long.class));
        verify(hotelImages, never()).delete(any());
    }

    /**
     * 记录在本事务读取之后被另一个请求删掉：{@code deleteById} 匹配 0 行。
     *
     * <p>旧实现不看返回值，照样记一条"删除酒店资料：xxx"的操作日志并让接口回 200 ——
     * 别人做的删除被记到这次请求头上，事后从操作日志里分不出真正执行删除的是谁。</p>
     */
    @Test
    @DisplayName("删除：记录在删除之前已被并发删除（0 行）时返回 404，不记成功日志")
    void deleteFailsWhenTheRowIsAlreadyGone() {
        when(hotels.selectOne(any())).thenReturn(hotel(56L, 1));
        when(itineraryDays.selectCount(any())).thenReturn(0L);
        when(hotels.deleteById(56L)).thenReturn(0);

        BusinessException error = assertThrows(BusinessException.class, () -> service.delete(56L, 7L));

        assertEquals(404, error.getStatus());
        assertEquals("RESOURCE_NOT_FOUND", error.getCode());
        verify(operationLog, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("删除：未被引用时先删图片记录再删酒店，并记录操作日志")
    void deleteRemovesImagesThenTheHotel() {
        when(hotels.selectOne(any())).thenReturn(hotel(54L, 1));
        when(itineraryDays.selectCount(any())).thenReturn(0L);

        service.delete(54L, 7L);

        ArgumentCaptor<QueryWrapper<HotelImage>> deleted = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(hotelImages).delete(deleted.capture());
        assertTrue(deleted.getValue().getSqlSegment().contains("hotel_id"),
                "应按 hotel_id 删除图片记录：" + deleted.getValue().getSqlSegment());
        verify(hotels).deleteById(54L);
        verify(operationLog).record(7L, "酒店", "DELETE", "HOTEL", 54L, "删除酒店资料：酒店 54");
    }

    /**
     * 引用检查与删除之间存在窗口：并发插入的行程只会被外键 {@code fk_day_hotel} 拦下。
     * 这一层兜底保证调用方拿到的是契约声明的 409，而不是被兜底处理成 500。
     */
    @Test
    @DisplayName("删除：并发插入行程引用导致外键拒绝时，翻译成 409 而不是 500")
    void deleteTranslatesForeignKeyViolationToConflict() {
        when(hotels.selectOne(any())).thenReturn(hotel(55L, 1));
        when(itineraryDays.selectCount(any())).thenReturn(0L);
        when(hotels.deleteById(55L))
                .thenThrow(new DataIntegrityViolationException("foreign key constraint fails"));

        BusinessException error = assertThrows(BusinessException.class, () -> service.delete(55L, 7L));

        assertEquals(409, error.getStatus());
        assertEquals("HOTEL_STATE_CONFLICT", error.getCode());
        verify(operationLog, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("公开详情：已发布线路 + 启用酒店 + 确实被引用时返回公开视图（不含后台字段）")
    void publicDetailReturnsPublicViewForTheArrangedHotel() {
        Hotel stored = hotel(70L, 1);
        stored.version = 9;
        stored.facilities = "[\"WIFI\",\"SWIMMING_POOL\"]";
        when(routes.selectById(11L)).thenReturn(publishedRoute(11L));
        when(hotels.selectById(70L)).thenReturn(stored);
        when(itineraryDays.selectCount(any())).thenReturn(1L);
        when(hotelImages.selectList(any())).thenReturn(List.of(
                image(70L, "https://example.com/1.jpg", 1),
                image(70L, "https://example.com/2.jpg", 2)));

        PublicHotelDetailView view = service.publicDetail(11L, 70L);

        assertEquals(70L, view.id());
        assertEquals("酒店 70", view.name());
        assertEquals("大理", view.city());
        assertEquals(4, view.starRating());
        assertEquals(List.of("WIFI", "SWIMMING_POOL"), view.facilities());
        assertEquals(List.of("https://example.com/1.jpg", "https://example.com/2.jpg"),
                view.images().stream().map(HotelImageView::url).toList());
        assertEquals("14:00", view.checkInTime());
        assertEquals("12:00", view.checkOutTime());
        assertEquals(new BigDecimal("102.8320000").doubleValue(), view.longitude(), 0.0);
        assertEquals("团队测试数据", view.dataSource());
    }

    @Test
    @DisplayName("公开详情：线路未发布时返回 404，且不查酒店")
    void publicDetailHidesHotelsOfUnpublishedRoutes() {
        TravelRoute draft = publishedRoute(12L);
        draft.status = "DRAFT";
        when(routes.selectById(12L)).thenReturn(draft);

        assertPublicNotFound(12L, 70L);

        when(routes.selectById(13L)).thenReturn(null);
        assertPublicNotFound(13L, 70L);

        TravelRoute deleted = publishedRoute(14L);
        deleted.deleted = 1;
        when(routes.selectById(14L)).thenReturn(deleted);
        assertPublicNotFound(14L, 70L);
        verify(hotels, never()).selectById(any(Long.class));
    }

    @Test
    @DisplayName("公开详情：酒店未被这条线路引用时返回 404")
    void publicDetailHidesHotelsNotArrangedInThatRoute() {
        when(routes.selectById(15L)).thenReturn(publishedRoute(15L));
        when(hotels.selectById(71L)).thenReturn(hotel(71L, 1));
        when(itineraryDays.selectCount(any())).thenReturn(0L);

        assertPublicNotFound(15L, 71L);
    }

    @Test
    @DisplayName("公开详情：酒店已停用或不存在时返回 404")
    void publicDetailHidesDisabledAndMissingHotels() {
        when(routes.selectById(16L)).thenReturn(publishedRoute(16L));
        when(hotels.selectById(72L)).thenReturn(hotel(72L, 0));
        assertPublicNotFound(16L, 72L);

        when(hotels.selectById(73L)).thenReturn(null);
        assertPublicNotFound(16L, 73L);
    }

    @Test
    @DisplayName("公开详情：不同原因的 404 使用同一文案，不外泄后台状态")
    void publicDetailUsesOneMessageForEveryNotFoundReason() {
        TravelRoute draft = publishedRoute(17L);
        draft.status = "OFFLINE";
        when(routes.selectById(17L)).thenReturn(draft);
        BusinessException unpublished = assertThrows(BusinessException.class,
                () -> service.publicDetail(17L, 74L));

        when(routes.selectById(18L)).thenReturn(publishedRoute(18L));
        when(hotels.selectById(74L)).thenReturn(hotel(74L, 0));
        BusinessException disabled = assertThrows(BusinessException.class,
                () -> service.publicDetail(18L, 74L));

        assertEquals(unpublished.getMessage(), disabled.getMessage(),
                "不同失败原因不能给出可区分的提示，否则等于一个公开的后台状态探测器");
        assertEquals("RESOURCE_NOT_FOUND", disabled.getCode());
        assertEquals(404, disabled.getStatus());
    }

    private void assertPublicNotFound(Long routeId, Long hotelId) {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.publicDetail(routeId, hotelId));
        assertEquals(404, error.getStatus());
        assertEquals("RESOURCE_NOT_FOUND", error.getCode());
    }

    // ===================== 夹具 =====================

    private static Hotel hotel(Long id, int status) {
        Hotel hotel = new Hotel();
        hotel.id = id;
        hotel.name = "酒店 " + id;
        hotel.city = "大理";
        hotel.address = "云南省昆明市测试路 1 号";
        hotel.contactPhone = "087112345678";
        hotel.coverUrl = "https://example.com/cover.jpg";
        hotel.longitude = new BigDecimal("102.8320000");
        hotel.latitude = new BigDecimal("24.8800000");
        hotel.starRating = 4;
        hotel.intro = "演示简介";
        hotel.facilities = "[\"WIFI\"]";
        hotel.checkInTime = "14:00";
        hotel.checkOutTime = "12:00";
        hotel.dataSource = "团队测试数据";
        hotel.status = status;
        // 乐观锁版本号：修改请求必须回传读取时的版本，夹具与请求都按 0 对齐。
        hotel.version = 0;
        hotel.createdAt = LocalDateTime.now();
        hotel.updatedAt = hotel.createdAt;
        return hotel;
    }

    private static HotelImage image(Long hotelId, String url, int sortOrder) {
        HotelImage image = new HotelImage();
        image.hotelId = hotelId;
        image.url = url;
        image.alt = "演示图片";
        image.sortOrder = sortOrder;
        return image;
    }

    private static TravelRoute publishedRoute(Long id) {
        TravelRoute route = new TravelRoute();
        route.id = id;
        route.name = "线路 " + id;
        route.status = "PUBLISHED";
        route.deleted = 0;
        return route;
    }

    @SuppressWarnings("unchecked")
    private UpdateWrapper<Hotel> capturedUpdate() {
        ArgumentCaptor<UpdateWrapper<Hotel>> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(hotels).update(ArgumentMatchers.isNull(), captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<QueryWrapper<Hotel>> captor() {
        return ArgumentCaptor.forClass(QueryWrapper.class);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Page<Hotel>> pageCaptor() {
        return ArgumentCaptor.forClass(Page.class);
    }
}
