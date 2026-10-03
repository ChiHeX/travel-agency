package com.travelagency.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.audit.OperationLogRecorder;
import com.travelagency.common.enums.AccountStatus;
import com.travelagency.common.enums.HotelFacility;
import com.travelagency.common.enums.RouteStatus;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.HotelCreateRequest;
import com.travelagency.domain.dto.HotelImageRequest;
import com.travelagency.domain.dto.HotelUpdateRequest;
import com.travelagency.domain.dto.HotelView;
import com.travelagency.domain.dto.PublicHotelDetailView;
import com.travelagency.domain.entity.Hotel;
import com.travelagency.domain.entity.HotelImage;
import com.travelagency.domain.entity.RouteItineraryDay;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.HotelImageMapper;
import com.travelagency.domain.mapper.HotelMapper;
import com.travelagency.domain.mapper.RouteItineraryDayMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 酒店模块 Service：后台酒店资料维护（契约 {@code Admin Resources} 的 {@code /admin/hotels} 一组端点）
 * 与线路下的酒店公开详情（契约 {@code GET /routes/{routeId}/hotels/{hotelId}}）。
 *
 * <p>酒店在 PRD 里只作为<b>线路行程资源</b>存在（PRD §10 酒店模型、§36 酒店资料管理）：
 * 不提供酒店订单、库存、房型销售与单独下单。用户端能看到酒店资料的两个入口是
 * 线路详情每日行程里的 {@code HotelSummaryView}（摘要）与本类提供的
 * {@link #publicDetail}（详情，图片与完整简介按需获取）——
 * 公开面刻意挂在<b>线路之下</b>：以"这条已发布线路确实安排了它"为门槛，
 * 才不会把后台维护的、与当前线路无关的酒店资料一并公开。</p>
 *
 * <p><b>{@code status} 的语义（三个端点共同保证）</b>：{@code DISABLED} 表示这家酒店不再使用 ——
 * 不能再被安排进新的每日行程（{@code AdminRouteService} 在写行程时以 422 拒绝），
 * 已引用它的行程不受影响（行程继续显示名称，但摘要与公开详情都不再给出，见
 * {@link #publicDetail}），停用也是删除的替代品：被行程引用的酒店不允许删除，见 {@link #delete}。</p>
 *
 * <p><b>修改带乐观锁</b>：{@link #update} 要求回传读取时的 {@code version}，
 * 与库内不一致时返回 {@code 409 HOTEL_VERSION_CONFLICT}，避免两位工作人员先后保存时
 * 后保存的人静默覆盖前一位的改动（与团期 {@code DepartureService#update} 同一口径）。
 * 酒店基础资料与图片在<b>同一个事务</b>内替换，不会出现"资料改了、图片还是旧的"。</p>
 *
 * <p><b>图片只登记外部 URL</b>：第一版由后台填写图片地址，项目不提供图片上传服务，
 * 因此替换 / 删除图片只删除 {@code hotel_image} 记录，<b>不会去删除外部图片文件</b>。</p>
 */
@Service
public class HotelService {

    private static final Logger log = LoggerFactory.getLogger(HotelService.class);

    private final HotelMapper hotels;
    private final HotelImageMapper hotelImages;
    private final RouteItineraryDayMapper itineraryDays;
    private final TravelRouteMapper routes;
    private final OperationLogRecorder operationLog;
    private final JsonMapper json;

    public HotelService(HotelMapper hotels, HotelImageMapper hotelImages,
                        RouteItineraryDayMapper itineraryDays, TravelRouteMapper routes,
                        OperationLogRecorder operationLog, JsonMapper json) {
        this.hotels = hotels;
        this.hotelImages = hotelImages;
        this.itineraryDays = itineraryDays;
        this.routes = routes;
        this.operationLog = operationLog;
        this.json = json;
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
     * 契约只声明了该参数存在与长度上限，未限定字段，沿用既有语义以免调用方行为发生变化。
     * {@code city} 是新增的精确筛选（与公开景点列表的 {@code city} 同口径）：
     * 城市是酒店最主要的分组维度，靠 {@code keyword} 模糊匹配会把"杭州"和"杭州路"混在一起。</p>
     *
     * <p>图片按本页酒店批量查询一次（{@link #imagesByHotel}），不做"每家酒店查一次"的 N+1。</p>
     */
    public PageResponse<HotelView> page(String keyword, String city, long page, long size) {
        QueryWrapper<Hotel> query = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String value = keyword.trim();
            query.and(w -> w.like("name", value).or().like("address", value).or().like("intro", value));
        }
        if (city != null && !city.isBlank()) {
            query.eq("city", city.trim());
        }
        Page<Hotel> result = hotels.selectPage(newPage(page, size),
                query.orderByDesc("created_at").orderByDesc("id"));
        Map<Long, List<HotelImage>> images = imagesByHotel(result.getRecords().stream().map(h -> h.id).toList());
        List<HotelView> items = result.getRecords().stream()
                .map(hotel -> HotelView.from(hotel, images.get(hotel.id), facilitiesOf(hotel)))
                .toList();
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
     * {@code status TINYINT NOT NULL DEFAULT 1} 的默认值一致；其它字段全部来自请求。
     * {@code version} 由服务端从 0 起算（契约 {@code HotelCreateRequest} 不含该字段，
     * 客户端提交会被严格反序列化拒绝）。图片与资料在同一事务内写入。</p>
     */
    @Transactional
    public HotelView create(HotelCreateRequest request, Long operatorId) {
        Hotel hotel = new Hotel();
        applyEditableFields(hotel, request);
        hotel.status = statusValue(request.hasStatus() ? request.status() : AccountStatus.ACTIVE);
        hotel.version = 0;
        hotels.insert(hotel);
        replaceImages(hotel.id, request.images());
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
     * <p>其余可空字段则一律写入（含 {@code null}）：契约把它们定义为"整体替换"，
     * 提交 {@code null} 就是清空旧资料。图片同样整体替换 —— 先删除该酒店的全部
     * {@code hotel_image} 记录，再按提交顺序重建；只删除记录，不删除外部图片文件。</p>
     *
     * <p>目标不存在时返回 404。旧实现是无条件 {@code updateById}：影响 0 行也照回 200 +
     * 请求体，调用方会以为一条不存在的酒店保存成功了。</p>
     *
     * <p><b>乐观锁</b>：提交的 {@code version} 与库内不一致说明这份资料已被他人修改，
     * 直接返回 409 {@code HOTEL_VERSION_CONFLICT}，本次修改不生效（图片也不会被替换）——
     * 两位工作人员各自打开同一条资料、先后保存时，后保存的人不会静默覆盖前一位的改动。
     * 版本判定用 {@link #lockHotel} 的当前读，与写入同处一把行锁内；
     * UPDATE 的 {@code WHERE version = ?} 是第二道防线。</p>
     *
     * <p>{@code SET} 里始终包含 {@code version = 版本 + 1}：只要 WHERE 命中就必然改变至少一个
     * 字段，"影响 0 行"因此不再有"字段没变化"这种歧义，也就不再依赖驱动的
     * {@code useAffectedRows} 语义（这一点在加版本号之前是必须靠约定说明的）。</p>
     *
     * <p><b>停用/启用另外记一条 {@code STATUS} 日志</b>：酒店资料没有独立的 PATCH 状态端点
     * （契约里状态只能随 PUT 提交），而"谁把这家酒店停用了"正是最需要追溯的动作。
     * 只记录笼统的"修改酒店资料"会让停用与改个电话在日志里长得一模一样。
     * 口径与 {@code DepartureService#updateStatus}、{@code AdminRouteService#updateStatus} 一致：
     * <b>状态确实发生变化时才记</b>，重复提交同一个状态不刷日志。</p>
     *
     * <p>"是否真的变化"的比较基准取自 {@link #lockHotel} 的当前读，并与本次写入同处一把行锁内：
     * 两人同时显式提交状态时，后者读到的是前者已提交的结果（而不是自己事务开始时的旧值），
     * 因此不会把"早已是 DISABLED"重复记成一次 ACTIVE → DISABLED（多记），
     * 也不会把 DISABLED → ACTIVE 这种真实变化漏掉（漏记）。</p>
     *
     * <p>修改的是<b>酒店基础资料</b>：历史订单保存的价格与出行人快照不受影响，
     * 也不会被这次修改覆盖（订单快照在下单时冻结，见 {@code OrderService}）。</p>
     */
    @Transactional
    public HotelView update(Long hotelId, HotelUpdateRequest request, Long operatorId) {
        // 锁内当前读是本方法唯一的判定基准：版本与状态比较都基于同一份最新已提交数据。
        Hotel current = lockHotel(hotelId);
        requireCurrentVersion(current, request.version());
        applyEditableFields(current, request.editableFields());
        UpdateWrapper<Hotel> update = new UpdateWrapper<Hotel>().eq("id", hotelId)
                // 乐观锁：版本不一致说明有人先改过，这条语句会匹配 0 行。
                .eq("version", request.version())
                .set("name", current.name)
                .set("city", current.city)
                .set("address", current.address)
                .set("contact_phone", current.contactPhone)
                .set("cover_url", current.coverUrl)
                .set("star_rating", current.starRating)
                .set("facilities", current.facilities)
                .set("check_in_time", current.checkInTime)
                .set("check_out_time", current.checkOutTime)
                .set("longitude", current.longitude)
                .set("latitude", current.latitude)
                .set("intro", current.intro)
                .set("data_source", current.dataSource)
                .set("version", request.version() + 1);
        if (request.hasStatus()) {
            update.set("status", statusValue(request.status()));
        }
        if (hotels.update(null, update) == 0) {
            // 锁已在本事务内持有，理论上不会再被别人改动；这里只是兜底，
            // 让"行不在了"与"版本对不上"各自得到正确的结论（404 / 409）。
            throw updateConflict(hotelId, request.version());
        }
        replaceImages(hotelId, request.images());
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
     * <p>删除前先删该酒店的图片记录（{@code fk_hotel_image_hotel} 没有级联）：
     * 图片是酒店的从属数据，酒店都没了就不该留下悬挂行。仍然只删除记录，
     * <b>不删除外部图片文件</b>。</p>
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
        Hotel hotel = lockHotel(hotelId);
        // 引用检查在酒店行锁内进行：行程安排（AdminRouteService#lockHotel）用的是同一把锁，
        // 因此"检查引用"与"并发新增引用"不会交错，409 是可靠的而不是靠外键兜出来的。
        if (itineraryDays.selectCount(
                new QueryWrapper<RouteItineraryDay>().eq("hotel_id", hotelId)) > 0) {
            throw new BusinessException(409, "HOTEL_STATE_CONFLICT",
                    "该酒店已被线路行程引用，不能删除；如需停止使用请把状态改为 DISABLED");
        }
        int deleted;
        try {
            hotelImages.delete(new QueryWrapper<HotelImage>().eq("hotel_id", hotelId));
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

    /**
     * 按主键读取单条酒店资料，对齐契约 {@code GET /admin/hotels/{hotelId}}（200 / 404）。
     *
     * <p>纯读路径：普通查询即可，不需要行锁，也不开事务（没有要保护的判定—写入窗口）。</p>
     *
     * <p><b>为什么必须有这个端点</b>：修改遇到乐观锁冲突（409 {@code HOTEL_VERSION_CONFLICT}）后，
     * 前端要拿到服务器最新版本才能"载入最新数据"或"保留我的修改并覆盖"。用列表端点按名称检索
     * 做不到：对方可能已经改过名称（旧名称检索不到），同名资料也可能超过一页 ——
     * 结果是接口正确报了冲突，用户却没有任何补救路径。</p>
     */
    public HotelView get(Long hotelId) {
        Hotel hotel = hotels.selectById(hotelId);
        if (hotel == null) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "酒店不存在");
        }
        return HotelView.from(hotel, imagesOf(hotelId), facilitiesOf(hotel));
    }

    /**
     * 线路下的酒店公开详情，对齐契约 {@code GET /routes/{routeId}/hotels/{hotelId}}。
     *
     * <p><b>三个条件同时成立才返回数据</b>：线路存在且已发布、酒店存在且启用、
     * 该线路的每日行程里确实安排了这家酒店。任何一条不满足都返回<b>同一个</b>
     * 404 {@code RESOURCE_NOT_FOUND}（文案也一致）：若按原因给出不同提示，
     * 无需登录的调用方就能用它枚举出后台有哪些酒店、哪家被停用了。</p>
     *
     * <p>为什么把门槛放在"线路确实安排了它"而不是直接开放 {@code /hotels/{hotelId}}：
     * 酒店在本项目里只是线路行程资源，整表公开等于把后台维护的、可能与任何线路都无关的
     * 酒店资料一起放出去（见类注释）。</p>
     *
     * <p>返回 {@link PublicHotelDetailView} 而不是后台 {@code HotelView}：
     * 不含 {@code version} / {@code status} / 审计时间，也不含后台的对接人电话。
     * 图片与设施由本方法装配，未录入时按契约返回 {@code []}。</p>
     */
    public PublicHotelDetailView publicDetail(Long routeId, Long hotelId) {
        TravelRoute route = routes.selectById(routeId);
        if (route == null || !RouteStatus.PUBLISHED.equals(route.status)
                || Integer.valueOf(1).equals(route.deleted)) {
            throw publicHotelNotFound();
        }
        Hotel hotel = hotels.selectById(hotelId);
        if (hotel == null || !AccountStatus.ACTIVE.equals(AccountStatus.of(hotel.status))) {
            throw publicHotelNotFound();
        }
        if (itineraryDays.selectCount(new QueryWrapper<RouteItineraryDay>()
                .eq("route_id", routeId).eq("hotel_id", hotelId)) == 0) {
            throw publicHotelNotFound();
        }
        return PublicHotelDetailView.from(hotel, imagesOf(hotelId), facilitiesOf(hotel));
    }

    /**
     * 公开详情统一的 404。三种不满足的条件共用同一条文案，避免把"酒店被停用"
     * 与"这家酒店根本不存在"区分出来（那是一次无需登录即可完成的后台状态探测）。
     */
    private static BusinessException publicHotelNotFound() {
        return new BusinessException(404, "RESOURCE_NOT_FOUND",
                "酒店资料不存在，或未在这条线路中公开");
    }

    // ------------------------------------------------------------------
    // 私有辅助
    // ------------------------------------------------------------------

    /**
     * 把契约允许的字段写进实体；文本字段去掉首尾空白，避免"看起来同名"的重复酒店。
     *
     * <p>{@code facilities} 在这里就转成 JSON 文本（库内是 JSON 列）并做枚举 / 去重校验：
     * 校验放在写入实体的同一处，创建与修改两条路径不会各漏一半。
     * {@code images} 不写实体 —— 它们是 {@code hotel_image} 的独立行，由
     * {@link #replaceImages} 处理。</p>
     */
    private void applyEditableFields(Hotel hotel, HotelCreateRequest request) {
        hotel.name = trim(request.name());
        hotel.city = trim(request.city());
        hotel.address = trim(request.address());
        hotel.contactPhone = trim(request.contactPhone());
        hotel.coverUrl = trim(request.coverUrl());
        hotel.starRating = request.starRating();
        hotel.facilities = facilitiesJson(request.facilities());
        hotel.checkInTime = trim(request.checkInTime());
        hotel.checkOutTime = trim(request.checkOutTime());
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

    /**
     * 设施标签 → 库内 JSON 数组文本（契约 {@code HotelFacility} 枚举，顺序按提交顺序保留）。
     *
     * <p>{@code @Pattern} 已经在请求层挡掉枚举外的取值，这里再走一次
     * {@link HotelFacility#normalize}：它同时负责"契约 {@code uniqueItems: true}"的去重校验，
     * 并保证写进 JSON 列的内容一定合法（JSON 列本身不认枚举）。
     * 空列表按 {@code null} 落库（未录入），读出来仍是契约要求的 {@code []}。</p>
     */
    private String facilitiesJson(List<String> facilities) {
        List<String> normalized;
        try {
            normalized = HotelFacility.normalize(facilities);
        } catch (IllegalArgumentException ex) {
            // 契约的 uniqueItems 违反：重复标签属于字段语义校验失败，按 docs/API.md 第 7 节回 422。
            throw new BusinessException(422, "VALIDATION_ERROR", ex.getMessage());
        }
        return normalized.isEmpty() ? null : json.writeValueAsString(normalized);
    }

    /**
     * 库内 JSON 文本 → 契约的字符串数组（无数据时给 {@code []}）。
     *
     * <p>解析失败只记警告并返回空列表：列类型是 JSON，正常写入的内容一定合法，
     * 走到这里说明有人直接改过库。为一行的脏数据让整张酒店列表接口回 500，
     * 会让工作人员连"把这条资料改回来"的入口都打不开。</p>
     */
    private List<String> facilitiesOf(Hotel hotel) {
        if (hotel == null || hotel.facilities == null || hotel.facilities.isBlank()) {
            return List.of();
        }
        try {
            String[] values = json.readValue(hotel.facilities, String[].class);
            return values == null ? List.of() : List.of(values);
        } catch (JacksonException ex) {
            log.warn("酒店 {} 的 facilities 不是合法 JSON 数组，按未录入处理", hotel.id, ex);
            return List.of();
        }
    }

    /**
     * 整体替换某家酒店的图片：先删除既有记录，再按提交顺序写入。
     *
     * <p>契约把 {@code images} 定义为整份资料的一部分（{@code PUT} 整体替换），
     * 省略或传空集合都表示"这家酒店不再有图片"。只删除 {@code hotel_image} 记录，
     * 不删除外部图片文件。</p>
     */
    private void replaceImages(Long hotelId, List<HotelImageRequest> images) {
        hotelImages.delete(new QueryWrapper<HotelImage>().eq("hotel_id", hotelId));
        if (images == null || images.isEmpty()) {
            return;
        }
        for (HotelImageRequest submitted : images) {
            HotelImage image = new HotelImage();
            image.hotelId = hotelId;
            image.url = trim(submitted.url());
            image.alt = trim(submitted.alt());
            image.sortOrder = submitted.sortOrder();
            hotelImages.insert(image);
        }
    }

    /** 某家酒店的图片，按 {@code sort_order} 升序、同序按主键（写入顺序）返回。 */
    private List<HotelImage> imagesOf(Long hotelId) {
        return hotelImages.selectList(new QueryWrapper<HotelImage>()
                .eq("hotel_id", hotelId).orderByAsc("sort_order").orderByAsc("id"));
    }

    /**
     * 一页酒店的图片，一次查询取回后按酒店分组（避免每家酒店各查一次）。
     * 排序与 {@link #imagesOf} 保持一致：{@code sort_order} 升序、同序按主键。
     */
    private Map<Long, List<HotelImage>> imagesByHotel(List<Long> hotelIds) {
        if (hotelIds == null || hotelIds.isEmpty()) {
            return Map.of();
        }
        List<HotelImage> images = hotelImages.selectList(new QueryWrapper<HotelImage>()
                .in("hotel_id", hotelIds).orderByAsc("sort_order").orderByAsc("id"));
        Map<Long, List<HotelImage>> grouped = new LinkedHashMap<>();
        for (HotelImage image : images) {
            grouped.computeIfAbsent(image.hotelId, key -> new java.util.ArrayList<>()).add(image);
        }
        return grouped;
    }

    /**
     * 按主键取酒店并加行锁（{@code SELECT ... FOR UPDATE} 当前读），不存在则 404。
     *
     * <p><b>写路径必须走当前读，不能用普通查询。</b>普通查询读的是本事务的一致性快照，
     * 而快照在本事务第一次读时就固定了：另一位工作人员在此期间提交的状态变更（停用/启用）
     * 对快照不可见，于是"读到的是哪个状态"与"实际写下去的是哪个状态"可能对不上 ——
     * {@link #update} 的状态审计日志会因此多记一次并未发生的变化，或漏记确实发生的变化。</p>
     *
     * <p>加锁后，状态判定、写入与日志都发生在同一把行锁内，且与其它同样加锁的写路径
     * （行程安排 {@code AdminRouteService#lockHotel}）串行化；锁随事务结束释放
     * （{@link #create} / {@link #update} / {@link #delete} 都是 {@code @Transactional}）。</p>
     */
    private Hotel lockHotel(Long hotelId) {
        Hotel hotel = hotels.selectOne(new QueryWrapper<Hotel>().eq("id", hotelId).last("FOR UPDATE"));
        if (hotel == null) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "酒店不存在");
        }
        return hotel;
    }

    /** 写入后回查并转契约视图；此时本事务已持有该行锁，回查走同一把锁。 */
    private HotelView requireView(Long hotelId) {
        Hotel hotel = lockHotel(hotelId);
        return HotelView.from(hotel, imagesOf(hotelId), facilitiesOf(hotel));
    }

    /**
     * 乐观锁早判：提交版本与库内不一致时立即返回 409，不让后面的写入先跑起来。
     *
     * <p>传入的必须是 {@link #lockHotel} 锁内当前读拿到的那一行，不能是普通读的快照：
     * 版本可能在"普通读"与"加锁读"之间被改掉，用快照判会得出过期结论。</p>
     */
    private void requireCurrentVersion(Hotel current, Integer submittedVersion) {
        if (!Objects.equals(current.version, submittedVersion)) {
            throw versionConflict(current.version, submittedVersion);
        }
    }

    /**
     * UPDATE 影响 0 行时的兜底判定：行不在了按 404，版本对不上按 409。
     *
     * <p>正常情况下本事务持有该行锁，这两种情况都不会发生；保留它是为了让
     * "0 行"永远不会被误解释成"修改成功"，也不会把版本冲突报成 404。</p>
     */
    private BusinessException updateConflict(Long hotelId, Integer submittedVersion) {
        Hotel latest = hotels.selectOne(new QueryWrapper<Hotel>().eq("id", hotelId).last("FOR UPDATE"));
        if (latest == null) {
            return new BusinessException(404, "RESOURCE_NOT_FOUND", "酒店不存在或已被删除，修改未生效");
        }
        return versionConflict(latest.version, submittedVersion);
    }

    /** 版本冲突的统一构造：早判与 0 行判定共用，保证同一原因在所有路径上文案与结果码一致。 */
    private static BusinessException versionConflict(Integer currentVersion, Integer submittedVersion) {
        return new BusinessException(409, "HOTEL_VERSION_CONFLICT",
                "酒店资料已被他人修改（当前版本 " + currentVersion + "，你提交的是 "
                        + submittedVersion + "），请查看最新数据后再决定是否覆盖");
    }
}
