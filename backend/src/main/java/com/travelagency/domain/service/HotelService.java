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

    /** 仅显式提交时更新状态；可空资料与图片整体替换，版本冲突时回滚。 */
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

    // 仅公开已发布线路实际引用的启用酒店；不满足条件统一返回 404。
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

    private static BusinessException publicHotelNotFound() {
        return new BusinessException(404, "RESOURCE_NOT_FOUND",
                "酒店资料不存在，或未在这条线路中公开");
    }

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

    private String facilitiesJson(List<String> facilities) {
        List<String> normalized;
        try {
            normalized = HotelFacility.normalize(facilities);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(422, "VALIDATION_ERROR", ex.getMessage());
        }
        return normalized.isEmpty() ? null : json.writeValueAsString(normalized);
    }

    /** 无效设施 JSON 按空列表处理，保留后台修复入口。 */
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

    private List<HotelImage> imagesOf(Long hotelId) {
        return hotelImages.selectList(new QueryWrapper<HotelImage>()
                .eq("hotel_id", hotelId).orderByAsc("sort_order").orderByAsc("id"));
    }

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
    // 当前读与写入共享行锁，避免用旧快照判断版本和状态。
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
