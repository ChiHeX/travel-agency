package com.travelagency.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.audit.OperationLogRecorder;
import com.travelagency.common.enums.AccountStatus;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.HotelUpsertRequest;
import com.travelagency.domain.dto.HotelView;
import com.travelagency.domain.entity.Hotel;
import com.travelagency.domain.entity.RouteItineraryDay;
import com.travelagency.domain.mapper.HotelMapper;
import com.travelagency.domain.mapper.RouteItineraryDayMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 酒店模块 Service：后台酒店资料维护（契约 {@code Admin Resources} 的 {@code /admin/hotels} 一组端点）。
 *
 * <p>酒店在 PRD 里只作为<b>线路行程资源</b>存在（PRD §10 酒店模型、§36 酒店资料管理）：
 * 不提供酒店订单、库存、房型销售与单独下单，因此本模块只有后台 CRUD，
 * 没有对外的公开浏览端点 —— 用户端看到酒店信息的唯一入口是线路详情的每日行程
 * （{@code RouteItineraryDay.hotelName}，由 {@code RouteService} / {@code AdminRouteService} 联查）。</p>
 *
 * <p>写接口此前直接写在 {@code AdminController} 里用 Mapper 操作数据库，本次迁移到 Service 层，
 * Controller 只做参数接收与响应封装（见 docs/DEVELOPMENT_GUIDE.md §3）。</p>
 */
@Service
public class HotelService {

    private final HotelMapper hotels;
    private final RouteItineraryDayMapper itineraryDays;
    private final OperationLogRecorder operationLog;

    public HotelService(HotelMapper hotels, RouteItineraryDayMapper itineraryDays,
                        OperationLogRecorder operationLog) {
        this.hotels = hotels;
        this.itineraryDays = itineraryDays;
        this.operationLog = operationLog;
    }

    /**
     * 后台酒店分页查询，对齐契约 {@code GET /admin/hotels}（{@code HotelPageEnvelope}）。
     *
     * <p>不过滤 {@code status}：停用的酒店同样要能被工作人员看到并改回来，与
     * {@code AttractionService#page} 的后台口径一致（停用是"下架"而不是"删除"）。</p>
     *
     * <p>排序按 {@code created_at DESC, id DESC}：只按创建时间排序时，同一秒内批量导入的
     * 酒店（{@code test-data.sql} 就是这么导入演示数据的）在不同页之间的先后顺序由 MySQL 决定，
     * 翻页会出现重复或漏项。</p>
     *
     * <p>{@code keyword} 与 {@code AdminController} 时期的实现同口径，匹配名称、地址与简介：
     * 契约只声明了该参数存在与长度上限，未限定字段，沿用既有语义以免调用方行为发生变化。</p>
     */
    public PageResponse<HotelView> page(String keyword, long page, long size) {
        QueryWrapper<Hotel> query = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String value = keyword.trim();
            query.and(w -> w.like("name", value).or().like("address", value).or().like("intro", value));
        }
        Page<Hotel> result = hotels.selectPage(newPage(page, size),
                query.orderByDesc("created_at").orderByDesc("id"));
        List<HotelView> items = result.getRecords().stream().map(HotelView::from).toList();
        return new PageResponse<>(items, (int) result.getCurrent(), (int) result.getSize(),
                (int) result.getTotal(), (int) result.getPages());
    }

    /** 分页参数归一：页码不为负，页大小钳到契约上限 100（防止一次拉全表）。 */
    private static Page<Hotel> newPage(long page, long size) {
        return new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100));
    }

    /**
     * 创建酒店资料，对齐契约 {@code POST /admin/hotels}（201 + {@code Location}）。
     *
     * <p>未提交 {@code status} 时按 {@link AccountStatus#ACTIVE} 建档，与库内
     * {@code status TINYINT NOT NULL DEFAULT 1} 的默认值一致；其它字段全部来自请求。</p>
     */
    @Transactional
    public HotelView create(HotelUpsertRequest request, Long operatorId) {
        Hotel hotel = new Hotel();
        applyEditableFields(hotel, request);
        hotel.status = statusValue(request.hasStatus() ? request.status() : AccountStatus.ACTIVE);
        hotels.insert(hotel);
        operationLog.record(operatorId, "酒店", "CREATE", "HOTEL", hotel.id,
                "新增酒店资料：" + hotel.name);
        // 回查以带回 created_at / updated_at 等数据库维护的字段，保证响应满足契约必填项。
        return requireView(hotel.id);
    }

    /**
     * 修改酒店资料，对齐契约 {@code PUT /admin/hotels/{hotelId}}。
     *
     * <p>只写契约允许的可编辑字段（显式列名 UPDATE），不整体回写实体：实体上还有
     * {@code created_at} / {@code updated_at}，{@code updateById} 会把回读到的旧
     * {@code updated_at} 一起写回，使数据库的 {@code ON UPDATE CURRENT_TIMESTAMP} 失效 ——
     * 于是"最近修改时间"永远停在建档那一刻，契约的 {@code updatedAt} 也就失去意义。</p>
     *
     * <p><b>只有请求显式提交了 {@code status} 才写这一列</b>，未提交时连列名都不出现在 SET 里。
     * 契约里 {@code status} 不是必填，{@code null} 表示"保持当前状态"；若把读到的旧值
     * {@code SET status = 旧值} 写回去，就变成一次普通资料编辑替数据库决定了状态：
     * 本事务在读取之后、写回之前，另一位管理员完成的停用会被这次编辑悄悄撤销
     * （读到 ACTIVE → 对方停用并提交 → 本事务把 ACTIVE 写回，酒店被重新启用）。</p>
     *
     * <p>目标不存在时返回 404。旧实现是无条件 {@code updateById}：影响 0 行也照回 200 +
     * 请求体，调用方会以为一条不存在的酒店保存成功了。</p>
     *
     * <p>UPDATE 影响 0 行同样按 404 处理：那说明这条记录在本事务读取之后被并发删除，
     * 此时既不能记"修改成功"的操作日志，也不能靠随后的回查把旧快照当成修改结果返回。
     * 0 行确实等价于"记录不存在"——MySQL 驱动默认回的是<b>匹配行数</b>而不是实际变更行数，
     * 写入内容与库内完全相同也会算 1 行，所以这个判定不会把"没有实质改动"误判成 404
     * （注意：若给 JDBC URL 加上 {@code useAffectedRows=true} 就会改成返回变更行数，该前提随之失效）。</p>
     *
     * <p><b>停用/启用另外记一条 {@code STATUS} 日志</b>：酒店资料没有独立的 PATCH 状态端点
     * （契约里状态只能随 PUT 提交），而"谁把这家酒店停用了"正是最需要追溯的动作。
     * 只记录笼统的"修改酒店资料"会让停用与改个电话在日志里长得一模一样。
     * 口径与 {@code DepartureService#updateStatus}、{@code AdminRouteService#updateStatus} 一致：
     * <b>状态确实发生变化时才记</b>，重复提交同一个状态不刷日志。</p>
     */
    @Transactional
    public HotelView update(Long hotelId, HotelUpsertRequest request, Long operatorId) {
        Hotel current = requireHotel(hotelId);
        applyEditableFields(current, request);
        UpdateWrapper<Hotel> update = new UpdateWrapper<Hotel>().eq("id", hotelId)
                .set("name", current.name)
                .set("address", current.address)
                .set("contact_phone", current.contactPhone)
                .set("longitude", current.longitude)
                .set("latitude", current.latitude)
                .set("intro", current.intro)
                .set("data_source", current.dataSource);
        if (request.hasStatus()) {
            update.set("status", statusValue(request.status()));
        }
        if (hotels.update(null, update) == 0) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "酒店不存在或已被删除，修改未生效");
        }
        operationLog.record(operatorId, "酒店", "UPDATE", "HOTEL", hotelId,
                "修改酒店资料：" + current.name);
        if (request.hasStatus() && !AccountStatus.of(current.status).equals(request.status())) {
            operationLog.record(operatorId, "酒店", "STATUS", "HOTEL", hotelId,
                    "酒店状态由 " + AccountStatus.of(current.status) + " 变更为 " + request.status());
        }
        return requireView(hotelId);
    }

    /**
     * 删除酒店资料，对齐契约 {@code DELETE /admin/hotels/{hotelId}}：
     * "删除未被行程引用的酒店资料"，成功返回 204，被引用返回 409。
     *
     * <p><b>为什么必须先查引用再删</b>：{@code route_itinerary_day.hotel_id} 以
     * {@code fk_day_hotel} 外键指向 {@code hotel}。旧实现直接 {@code deleteById}，被行程引用的酒店
     * 会让外键拒绝删除并抛出 {@code DataIntegrityViolationException}，被全局兜底处理成 <b>500</b>，
     * 而契约在这里声明的语义是 409 冲突 —— 工作人员只会看到"服务暂时不可用"，
     * 无从知道真正的原因是"这家酒店还在某条线路的行程里"。</p>
     *
     * <p>删除只对"未被引用"的酒店开放，不提供级联：把每日行程里的酒店一起清掉会静默改变
     * 已上架线路的行程内容（用户端线路详情的"住宿"一行会凭空消失）。需要让酒店停止使用时
     * 应改状态为 {@code DISABLED}；若确实要从行程里摘掉，应先由行程管理把该天的酒店换掉或置空。</p>
     *
     * <p>删除影响 0 行同样按 404 处理，与 {@link #update} 同一口径：读取与删除之间该酒店被
     * 另一个请求删掉时，{@code deleteById} 匹配不到任何行，而旧实现不看返回值，照样记一条
     * "删除酒店"的操作日志并回 200 —— 操作日志会把别人做的删除记到这次请求头上。</p>
     */
    @Transactional
    public void delete(Long hotelId, Long operatorId) {
        Hotel hotel = requireHotel(hotelId);
        if (itineraryDays.selectCount(
                new QueryWrapper<RouteItineraryDay>().eq("hotel_id", hotelId)) > 0) {
            throw new BusinessException(409, "HOTEL_STATE_CONFLICT",
                    "该酒店已被线路行程引用，不能删除；如需停止使用请把状态改为 DISABLED");
        }
        int deleted;
        try {
            deleted = hotels.deleteById(hotelId);
        } catch (DataIntegrityViolationException ex) {
            // 竞态兜底：引用检查与删除之间，另一个事务把该酒店排进了某天的行程（外键拒绝删除）。
            // 这一层保证并发下拿到的仍是契约声明的 409，而不是 500。
            throw new BusinessException(409, "HOTEL_STATE_CONFLICT",
                    "该酒店刚被线路行程引用，不能删除，请刷新后重试");
        }
        if (deleted == 0) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "酒店不存在或已被删除");
        }
        operationLog.record(operatorId, "酒店", "DELETE", "HOTEL", hotelId,
                "删除酒店资料：" + hotel.name);
    }

    /** 把契约允许的字段写进实体；文本字段去掉首尾空白，避免"看起来同名"的重复酒店。 */
    private static void applyEditableFields(Hotel hotel, HotelUpsertRequest request) {
        hotel.name = trim(request.name());
        hotel.address = trim(request.address());
        hotel.contactPhone = trim(request.contactPhone());
        hotel.longitude = decimal(request.longitude());
        hotel.latitude = decimal(request.latitude());
        hotel.intro = request.intro();
        hotel.dataSource = trim(request.dataSource());
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    /** 契约 {@code Longitude}/{@code Latitude} 是 JSON number，库内是 {@code DECIMAL(10,7)}。 */
    private static BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    /** 契约 {@code AccountStatus} → 库内 {@code TINYINT} 1/0 的唯一转换点。 */
    private static int statusValue(String status) {
        return AccountStatus.ACTIVE.equals(status) ? 1 : 0;
    }

    private Hotel requireHotel(Long hotelId) {
        Hotel hotel = hotels.selectById(hotelId);
        if (hotel == null) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "酒店不存在");
        }
        return hotel;
    }

    /** 写入后回查并转契约视图；行在本次事务中被并发删除时同样按 404 处理。 */
    private HotelView requireView(Long hotelId) {
        return HotelView.from(requireHotel(hotelId));
    }
}
