package com.travelagency.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.audit.OperationLogRecorder;
import com.travelagency.common.enums.AccountStatus;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.AttractionDetailView;
import com.travelagency.domain.dto.AttractionUpsertRequest;
import com.travelagency.domain.dto.AttractionView;
import com.travelagency.domain.dto.DepartureView;
import com.travelagency.domain.entity.Attraction;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.PlaceGuideItem;
import com.travelagency.domain.entity.RouteItineraryItem;
import com.travelagency.domain.entity.TravelGuideArticle;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.AttractionMapper;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.PlaceGuideItemMapper;
import com.travelagency.domain.mapper.RouteItineraryItemMapper;
import com.travelagency.domain.mapper.TravelGuideArticleMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 景点模块 Service：公开浏览（契约 {@code Attractions}）与后台维护（契约 {@code Admin Resources}
 * 的 {@code /admin/attractions} 一组端点）共用一套数据访问与映射。
 *
 * <p>后台写接口此前直接写在 {@code AdminController} 里用 Mapper 操作数据库，本次迁移到
 * Service 层，Controller 只做参数接收与响应封装（见 docs/DEVELOPMENT_GUIDE.md §3）。</p>
 */
@Service
public class AttractionService {

    private final AttractionMapper attractions;
    private final RouteItineraryItemMapper itineraryItems;
    private final TravelRouteMapper routes;
    private final DepartureMapper departures;
    private final PlaceGuideItemMapper placeGuideItems;
    private final TravelGuideArticleMapper guideArticles;
    private final OperationLogRecorder operationLog;

    public AttractionService(AttractionMapper attractions, RouteItineraryItemMapper itineraryItems,
                             TravelRouteMapper routes, DepartureMapper departures,
                             PlaceGuideItemMapper placeGuideItems, TravelGuideArticleMapper guideArticles,
                             OperationLogRecorder operationLog) {
        this.attractions = attractions;
        this.itineraryItems = itineraryItems;
        this.routes = routes;
        this.departures = departures;
        this.placeGuideItems = placeGuideItems;
        this.guideArticles = guideArticles;
        this.operationLog = operationLog;
    }

    // ------------------------------------------------------------------
    // 公开浏览（契约 Attractions）
    // ------------------------------------------------------------------

    /**
     * 景点公开分页查询，对齐契约 {@code GET /attractions}（{@code AttractionPageEnvelope}）。
     *
     * <p>只返回已启用的景点：停用是"从对外页面撤下"的唯一手段，这里必须与详情端点同口径。
     * 排序按名称升序，与后台列表（创建时间倒序）刻意不同 —— 用户端是"按名字找地方"的浏览场景，
     * 后台是"刚录入的先看到"的维护场景。</p>
     *
     * <p>排序补了 {@code id} 作为第二关键字：{@code name} 上没有唯一约束，"人民公园"这类重名景点
     * 在不同城市里很常见，只按 {@code name} 排序时重名行的先后由 MySQL 决定，翻页会出现重复或漏项
     * （与后台列表用 {@code created_at DESC, id DESC} 挡掉的是同一类问题）。</p>
     *
     * <p>与后台列表共用 {@link AttractionView} 映射：此前该端点直出 {@code Attraction} 实体，
     * 响应里的 {@code status} 是整数 1、坐标被全局 {@code BigDecimal} 序列化器变成两位小数字符串，
     * 与契约 {@code Attraction} 的 {@code AccountStatus} 和 JSON number 都不符。</p>
     */
    public PageResponse<AttractionView> pagePublic(String keyword, String city, long page, long size) {
        QueryWrapper<Attraction> query = new QueryWrapper<Attraction>().eq("status", 1);
        if (keyword != null && !keyword.isBlank()) {
            String value = keyword.trim();
            query.and(w -> w.like("name", value).or().like("intro", value));
        }
        if (city != null && !city.isBlank()) {
            query.eq("city", city.trim());
        }
        Page<Attraction> result = attractions.selectPage(newPage(page, size),
                query.orderByAsc("name").orderByAsc("id"));
        return toViewPage(result);
    }

    /**
     * 景点公开详情，对齐契约 {@code GET /attractions/{attractionId}}。
     *
     * <p>只返回已启用（{@code status = 1}）的景点：停用是"从对外页面撤下"的唯一手段，
     * 若这里放行，后台停用就会变成一个只影响列表、不影响详情页的空操作。</p>
     */
    public AttractionDetailView detail(Long attractionId) {
        Attraction attraction = attractions.selectById(attractionId);
        if (attraction == null || !Integer.valueOf(1).equals(attraction.status)) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "地点不存在");
        }
        List<Long> routeIds = itineraryItems.publishedRouteIdsForAttraction(attractionId);
        if (routeIds.isEmpty()) {
            return new AttractionDetailView(AttractionView.from(attraction), List.of());
        }

        Map<Long, TravelRoute> routeById = routes.selectBatchIds(routeIds).stream()
                .collect(Collectors.toMap(route -> route.id, Function.identity()));
        List<AttractionDetailView.Trip> trips = departures.selectList(new QueryWrapper<Departure>()
                        .in("route_id", routeIds).eq("status", "OPEN")
                        .ge("start_date", LocalDate.now()).orderByAsc("start_date", "id"))
                .stream().filter(departure -> routeById.containsKey(departure.routeId))
                .map(departure -> {
                    TravelRoute route = routeById.get(departure.routeId);
                    return new AttractionDetailView.Trip(departure.id, route.id, route.name,
                            route.departureCity, departure.startDate, departure.endDate,
                            departure.adultPrice, departure.childPrice,
                            DepartureView.availableSeats(departure));
                }).toList();
        return new AttractionDetailView(AttractionView.from(attraction), trips);
    }

    // ------------------------------------------------------------------
    // 后台景点管理（契约 Admin Resources: /admin/attractions）
    // ------------------------------------------------------------------

    /**
     * 后台景点分页查询，对齐契约 {@code GET /admin/attractions}（{@code AttractionPageEnvelope}）。
     *
     * <p>与公开列表的区别：后台不过滤 {@code status}，停用的景点同样要能被工作人员看到并改回来。
     * 排序按 {@code created_at DESC, id DESC}：只按创建时间排序时，同一秒内批量导入的景点
     * 在不同页之间的先后顺序不稳定，翻页会出现重复或漏项。</p>
     */
    public PageResponse<AttractionView> page(String keyword, long page, long size) {
        QueryWrapper<Attraction> query = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String value = keyword.trim();
            query.and(w -> w.like("name", value).or().like("intro", value).or().like("city", value));
        }
        Page<Attraction> result = attractions.selectPage(newPage(page, size),
                query.orderByDesc("created_at").orderByDesc("id"));
        return toViewPage(result);
    }

    /** 实体分页 → 契约视图分页；公开列表与后台列表共用，保证同一个景点在任何接口上形状一致。 */
    private static PageResponse<AttractionView> toViewPage(Page<Attraction> result) {
        List<AttractionView> items = result.getRecords().stream().map(AttractionView::from).toList();
        return new PageResponse<>(items, (int) result.getCurrent(), (int) result.getSize(),
                (int) result.getTotal(), (int) result.getPages());
    }

    /** 分页参数归一：页码不为负，页大小钳到契约上限 100（防止一次拉全表）。 */
    private static Page<Attraction> newPage(long page, long size) {
        return new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100));
    }

    /**
     * 创建景点，对齐契约 {@code POST /admin/attractions}（201 + {@code Location}）。
     *
     * <p>未提交 {@code status} 时按 {@link AccountStatus#ACTIVE} 建档，与库内
     * {@code status TINYINT NOT NULL DEFAULT 1} 的默认值一致；其它字段全部来自请求。</p>
     */
    @Transactional
    public AttractionView create(AttractionUpsertRequest request, Long operatorId) {
        Attraction attraction = new Attraction();
        applyEditableFields(attraction, request);
        attraction.status = statusValue(request.hasStatus() ? request.status() : AccountStatus.ACTIVE);
        attractions.insert(attraction);
        operationLog.record(operatorId, "景点", "CREATE", "ATTRACTION", attraction.id,
                "新增景点：" + attraction.name);
        // 回查以带回 created_at / updated_at 等数据库维护的字段，保证响应满足契约必填项。
        return requireView(attraction.id);
    }

    /**
     * 修改景点，对齐契约 {@code PUT /admin/attractions/{attractionId}}。
     *
     * <p>只写契约允许的可编辑字段（显式列名 UPDATE），不整体回写实体：实体上还有
     * {@code created_at} / {@code updated_at}，{@code updateById} 会把回读到的旧
     * {@code updated_at} 一起写回，使数据库的 {@code ON UPDATE CURRENT_TIMESTAMP} 失效 ——
     * 于是"最近修改时间"永远停在建档那一刻，契约的 {@code updatedAt} 也就失去意义。</p>
     *
     * <p>目标不存在时返回 404。旧实现是无条件 {@code updateById}：影响 0 行也照回 200 +
     * 请求体，调用方会以为一条不存在的景点保存成功了。</p>
     */
    @Transactional
    public AttractionView update(Long attractionId, AttractionUpsertRequest request, Long operatorId) {
        Attraction current = requireAttraction(attractionId);
        applyEditableFields(current, request);
        // status 未提交时保留库内现值，不把停用的景点悄悄重新启用。
        if (request.hasStatus()) {
            current.status = statusValue(request.status());
        }
        attractions.update(null, new UpdateWrapper<Attraction>().eq("id", attractionId)
                .set("name", current.name)
                .set("city", current.city)
                .set("address", current.address)
                .set("longitude", current.longitude)
                .set("latitude", current.latitude)
                .set("intro", current.intro)
                .set("data_source", current.dataSource)
                .set("status", current.status));
        operationLog.record(operatorId, "景点", "UPDATE", "ATTRACTION", attractionId,
                "修改景点：" + current.name);
        return requireView(attractionId);
    }

    /**
     * 删除景点，对齐契约 {@code DELETE /admin/attractions/{attractionId}}：
     * "删除未被行程引用的景点资料"，成功返回 204，被引用返回 409。
     *
     * <p><b>为什么必须先查引用再删</b>：{@code route_itinerary_item}、{@code place_guide_item}
     * 与 {@code travel_guide_article} 三张表都以 {@code attraction_id} 外键指向 {@code attraction}。
     * 旧实现直接 {@code deleteById}，被引用的景点会让外键拒绝删除并抛出
     * {@code DataIntegrityViolationException}，被全局兜底处理成 <b>500</b>，
     * 而契约在这里声明的语义是 409 冲突 —— 工作人员只会看到"服务暂时不可用"，
     * 无从知道真正的原因是"这条景点还被线路用着"。</p>
     *
     * <p>删除只对"未被引用"的景点开放，不提供级联：把线路行程里的景点一起删掉会静默改变
     * 已上架线路的行程内容。需要让景点不再对外可见时应改状态为 {@code DISABLED}。</p>
     */
    @Transactional
    public void delete(Long attractionId, Long operatorId) {
        Attraction attraction = requireAttraction(attractionId);
        String referencingModule = referencingModule(attractionId);
        if (referencingModule != null) {
            throw new BusinessException(409, "ATTRACTION_STATE_CONFLICT",
                    "该景点已被" + referencingModule + "引用，不能删除；如需下架请把状态改为 DISABLED");
        }
        try {
            attractions.deleteById(attractionId);
        } catch (DataIntegrityViolationException ex) {
            // 竞态兜底：引用检查与删除之间，另一个事务插入了引用行（外键拒绝删除）。
            // 这一层保证并发下拿到的仍是契约声明的 409，而不是 500。
            throw new BusinessException(409, "ATTRACTION_STATE_CONFLICT",
                    "该景点刚被其它资料引用，不能删除，请刷新后重试");
        }
        operationLog.record(operatorId, "景点", "DELETE", "ATTRACTION", attractionId,
                "删除景点：" + attraction.name);
    }

    /**
     * 返回第一个引用该景点的模块名，未被引用时返回 {@code null}。
     *
     * <p>三张引用表逐个点名，是为了让 409 的提示能直接说明"被什么挡住"，
     * 而不是笼统的"资源冲突"。</p>
     */
    private String referencingModule(Long attractionId) {
        if (itineraryItems.selectCount(
                new QueryWrapper<RouteItineraryItem>().eq("attraction_id", attractionId)) > 0) {
            return "线路行程";
        }
        if (placeGuideItems.selectCount(
                new QueryWrapper<PlaceGuideItem>().eq("attraction_id", attractionId)) > 0) {
            return "地点指南";
        }
        if (guideArticles.selectCount(
                new QueryWrapper<TravelGuideArticle>().eq("attraction_id", attractionId)) > 0) {
            return "攻略文章";
        }
        return null;
    }

    /** 把契约允许的字段写进实体；文本字段去掉首尾空白，避免"看起来同名"的重复景点。 */
    private static void applyEditableFields(Attraction attraction, AttractionUpsertRequest request) {
        attraction.name = trim(request.name());
        attraction.city = trim(request.city());
        attraction.address = trim(request.address());
        attraction.longitude = decimal(request.longitude());
        attraction.latitude = decimal(request.latitude());
        attraction.intro = request.intro();
        attraction.dataSource = trim(request.dataSource());
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

    private Attraction requireAttraction(Long attractionId) {
        Attraction attraction = attractions.selectById(attractionId);
        if (attraction == null) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "景点不存在");
        }
        return attraction;
    }

    /** 写入后回查并转契约视图；行在本次事务中被并发删除时同样按 404 处理。 */
    private AttractionView requireView(Long attractionId) {
        return AttractionView.from(requireAttraction(attractionId));
    }
}
