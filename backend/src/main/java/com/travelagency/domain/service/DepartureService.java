package com.travelagency.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.audit.OperationLogRecorder;
import com.travelagency.common.enums.DepartureStatus;
import com.travelagency.common.enums.OrderStatus;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.DepartureUpsertRequest;
import com.travelagency.domain.dto.DepartureView;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.Guide;
import com.travelagency.domain.entity.TravelOrder;
import com.travelagency.domain.entity.TravelRoute;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.GuideMapper;
import com.travelagency.domain.mapper.TravelOrderMapper;
import com.travelagency.domain.mapper.TravelRouteMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class DepartureService {

    private final DepartureMapper departureMapper;
    private final TravelOrderMapper orderMapper;
    private final TravelRouteMapper routeMapper;
    private final GuideMapper guideMapper;
    private final OperationLogRecorder operationLog;

    /** 契约 DepartureStatus 的全部取值，供后台直接改状态与列表筛选时校验。 */
    private static final List<String> ALL_STATUSES = List.of(
            DepartureStatus.DRAFT, DepartureStatus.OPEN, DepartureStatus.FULL,
            DepartureStatus.CLOSED, DepartureStatus.TRAVELLING, DepartureStatus.FINISHED,
            DepartureStatus.CANCELLED);

    /** 状态枚举非法时统一的说明文案，避免列表筛选与状态变更给出两种口径。 */
    private static final String STATUS_MESSAGE =
            "团期状态只能是 DRAFT、OPEN、FULL、CLOSED、TRAVELLING、FINISHED 或 CANCELLED";

    /**
     * 允许导游"开始行程"的前置状态：已开售 / 已满 / 已截止，但都还没出发。
     *
     * <p>刻意排除 {@link DepartureStatus#DRAFT}：新团期由 {@link #save} 建成 DRAFT，
     * 而 {@code OrderService#create} 只接受 {@link DepartureStatus#OPEN} 的团期下单，
     * 故 DRAFT 团期必然零订单——允许它"出发"只会掩盖后台漏上架，因此返回 409
     * 要求先把团期上架。同时也排除 TRAVELLING（已在行程中，重复出发）、
     * FINISHED 与 CANCELLED（终态）。</p>
     */
    private static final List<String> STARTABLE_STATUSES = List.of(
            DepartureStatus.OPEN, DepartureStatus.FULL, DepartureStatus.CLOSED);

    public DepartureService(DepartureMapper departureMapper, TravelOrderMapper orderMapper,
                            TravelRouteMapper routeMapper, GuideMapper guideMapper,
                            OperationLogRecorder operationLog) {
        this.departureMapper = departureMapper;
        this.orderMapper = orderMapper;
        this.routeMapper = routeMapper;
        this.guideMapper = guideMapper;
        this.operationLog = operationLog;
    }

    /**
     * 后台团期分页查询，对齐契约 GET /admin/departures：
     * 返回分页信封而非裸数组，items 为契约 Departure 视图（含 availableSeats / routeName / guideName）。
     *
     * <p>{@code status} 必须是契约 {@code DepartureStatus} 之一，传非法值返回 422，
     * 避免静默返回空列表让调用方误判成"没有符合条件的团期"。</p>
     */
    public PageResponse<DepartureView> page(Long routeId, Long guideId, String status,
                                            LocalDate startDateFrom, LocalDate startDateTo, int page, int size) {
        QueryWrapper<Departure> query = new QueryWrapper<>();
        if (routeId != null) {
            query.eq("route_id", routeId);
        }
        if (guideId != null) {
            query.eq("guide_id", guideId);
        }
        if (status != null && !status.isBlank()) {
            String value = status.trim();
            if (!ALL_STATUSES.contains(value)) {
                throw new BusinessException(422, "VALIDATION_ERROR", STATUS_MESSAGE);
            }
            query.eq("status", value);
        }
        if (startDateFrom != null) {
            query.ge("start_date", startDateFrom);
        }
        if (startDateTo != null) {
            query.le("start_date", startDateTo);
        }
        query.orderByAsc("start_date");
        Page<Departure> result = departureMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)), query);
        return toViewPage(result);
    }

    /** 团期管理详情，对齐契约 GET /admin/departures/{departureId}。 */
    public DepartureView detail(Long departureId) {
        Departure departure = departureMapper.selectById(departureId);
        if (departure == null) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "团期不存在");
        }
        return DepartureView.from(departure, routeName(departure.routeId), guideName(departure.guideId));
    }

    /** 把团期实体分页转成契约视图分页，批量补齐 routeName / guideName，避免 N+1。 */
    public PageResponse<DepartureView> toViewPage(Page<Departure> result) {
        List<Departure> records = result.getRecords();
        Map<Long, String> routeNames = routeNameMap(records);
        Map<Long, String> guideNames = guideNameMap(records);
        List<DepartureView> items = records.stream()
                .map(d -> DepartureView.from(d, routeNames.get(d.routeId), guideNames.get(d.guideId)))
                .toList();
        return new PageResponse<>(items, (int) result.getCurrent(), (int) result.getSize(),
                (int) result.getTotal(), (int) result.getPages());
    }

    /** 单个团期转契约视图（供其它模块复用）。 */
    public DepartureView toView(Departure departure) {
        if (departure == null) {
            return null;
        }
        return DepartureView.from(departure, routeName(departure.routeId), guideName(departure.guideId));
    }

    /**
     * 某位导游的全部团期（不分页，按出发日期升序），供导游工作台按状态分组。
     *
     * <p>复用同一套 routeName / guideName 批量联查与视图映射，
     * 避免工作台另行拼装字段导致同一团期在不同接口上口径不一致。</p>
     */
    public List<DepartureView> listOfGuide(Long guideId) {
        if (guideId == null) {
            return List.of();
        }
        List<Departure> departures = departureMapper.selectList(new QueryWrapper<Departure>()
                .eq("guide_id", guideId).orderByAsc("start_date"));
        Map<Long, String> routeNames = routeNameMap(departures);
        Map<Long, String> guideNames = guideNameMap(departures);
        return departures.stream()
                .map(d -> DepartureView.from(d, routeNames.get(d.routeId), guideNames.get(d.guideId)))
                .toList();
    }

    // ------------------------------------------------------------------
    // 后台团期管理（契约 Admin Departures）
    // ------------------------------------------------------------------

    /**
     * 创建团期，对齐契约 POST /admin/departures（201 + Location + DepartureEnvelope）。
     *
     * <p>服务端决定的字段一律不接受客户端输入：新建团期固定为 {@link DepartureStatus#DRAFT}
     * （{@code OrderService#create} 只接受 OPEN 团期下单，因此 DRAFT 团期必然零订单，
     * 必须先经 {@code PATCH /admin/departures/{departureId}/status} 上架才能售卖），
     * {@code reservedPeople} / {@code confirmedPeople} 从 0 起算，{@code version} 归零。</p>
     */
    @Transactional
    public DepartureView create(DepartureUpsertRequest request, Long operatorId) {
        validateEditableFields(request);
        requireRoute(request.routeId());
        requireGuide(request.guideId());
        Departure departure = new Departure();
        applyEditableFields(departure, request);
        departure.status = DepartureStatus.DRAFT;
        departure.reservedPeople = 0;
        departure.confirmedPeople = 0;
        departure.version = 0;
        checkGuideConflict(departure);
        departureMapper.insert(departure);
        operationLog.record(operatorId, "团期", "CREATE", "DEPARTURE", departure.id,
                "线路 " + request.routeId() + " 新建团期：" + request.startDate() + " 至 " + request.endDate());
        // 回查以带回 created_at / updated_at 等数据库默认值，并保证响应与契约一致。
        return detail(departure.id);
    }

    /**
     * 修改团期，对齐契约 PUT /admin/departures/{departureId}。
     *
     * <p>只覆盖契约允许的可编辑字段。{@code status}、{@code reservedPeople}、
     * {@code confirmedPeople}、{@code version} 不受本次修改影响，因此用显式字段 UPDATE
     * 而不是把实体整体写回：名额计数由下单 / 支付 / 退款链路维护，一旦被这里的整体写回覆盖，
     * 已报名人数会凭空消失（可用名额虚增，进而超卖）。</p>
     *
     * <p><b>两条业务闸门都放进 UPDATE 的 WHERE，并按影响行数判定结果</b>，而不是
     * "先读、判断、再无条件下写"：</p>
     * <ol>
     *   <li><b>名额闸门</b> {@code reserved_people + confirmed_people <= 新上限}。
     *       读取已占名额与写入新上限之间存在窗口，期间并发下单会占走名额；
     *       只做应用层比较会写出「已占人数 &gt; 最大人数」的团期，
     *       而 {@code availableSeats} 会把负数钳成 0，把超卖藏在接口背后。
     *       WHERE 条件与 UPDATE 同一条语句，行锁保证判定与写入原子生效；</li>
     *   <li><b>改挂闸门</b> {@code status = DRAFT}（仅在 {@code routeId} 变化时加）。
     *       {@code travel_order} 同时保存 {@code route_id} 与 {@code departure_id}，
     *       订单创建时从团期读出线路；"先查有没有订单、再改线路"无法与并发下单串行化 ——
     *       检查通过后、写入前新插入的订单仍会留下旧线路 id。下单只接受 {@code OPEN} 团期，
     *       因此把可改挂范围限定在尚未开放报名的草稿团期，窗口就不存在了。</li>
     * </ol>
     *
     * <p>影响行数为 0 时回读最新行给出可定位的 409（见 {@link #resolveWriteConflict}）；
     * 若回读确认目标状态已达成，则按成功处理，避免驱动在
     * {@code useAffectedRows=true} 下把"字段无变化"报成 0 行时被误判为冲突。</p>
     */
    @Transactional
    public DepartureView update(Long departureId, DepartureUpsertRequest request, Long operatorId) {
        Departure existing = requireDeparture(departureId);
        validateEditableFields(request);
        requireRoute(request.routeId());
        requireGuide(request.guideId());
        boolean rebinding = !Objects.equals(existing.routeId, request.routeId());
        if (rebinding) {
            requireRebindable(departureId, existing);
        }
        // 读得到的明显违规先按字段语义返回 422；读取之后才出现的并发占位由上面的 WHERE 闸门兜住。
        int occupied = DepartureView.occupiedSeats(existing);
        if (request.maxPeople() < occupied) {
            throw new BusinessException(422, "VALIDATION_ERROR", capacityMessage(occupied));
        }
        // 冲突检测用待写入的取值，且排除自身（candidate.id），否则每次保存都会与自己撞上。
        Departure candidate = new Departure();
        candidate.id = departureId;
        applyEditableFields(candidate, request);
        checkGuideConflict(candidate);
        UpdateWrapper<Departure> update = new UpdateWrapper<Departure>()
                .eq("id", departureId)
                .set("route_id", request.routeId())
                .set("start_date", request.startDate())
                .set("end_date", request.endDate())
                .set("adult_price", request.adultPrice())
                .set("child_price", request.childPrice())
                .set("max_people", request.maxPeople())
                .set("guide_id", request.guideId())
                // 名额闸门：{0} 由 MyBatis-Plus 绑定为占位参数，不做字符串拼接。
                .apply("reserved_people + confirmed_people <= {0}", request.maxPeople());
        if (rebinding) {
            update.eq("status", DepartureStatus.DRAFT);
        }
        if (departureMapper.update(null, update) == 0) {
            resolveWriteConflict(departureId, request, rebinding);
        }
        operationLog.record(operatorId, "团期", "UPDATE", "DEPARTURE", departureId,
                "修改团期：" + request.startDate() + " 至 " + request.endDate());
        return detail(departureId);
    }

    /**
     * 改挂线路的前置校验。
     *
     * <p>两道条件：团期没有订单，且仍是 {@link DepartureStatus#DRAFT}。
     * 前者覆盖"曾经上架、产生订单后又退回草稿"的情形，后者让并发下单不可能与本操作交错 ——
     * 下单只接受 OPEN 团期。真正的一致性由 UPDATE 里的 {@code status = DRAFT} 条件保证，
     * 这里只是为了在正常情况下给出可定位的错误码与文案。</p>
     */
    private void requireRebindable(Long departureId, Departure existing) {
        if (orderCount(departureId) > 0) {
            throw new BusinessException(409, "DEPARTURE_STATE_CONFLICT",
                    "该团期已产生订单，不能改挂到其它线路");
        }
        if (!DepartureStatus.DRAFT.equals(existing.status)) {
            throw new BusinessException(409, "DEPARTURE_STATE_CONFLICT",
                    "只有草稿状态的团期可以改挂线路，请先把团期状态改回 DRAFT");
        }
    }

    /**
     * UPDATE 匹配 0 行时的原因判定。
     *
     * <p>WHERE 里只有主键与两个业务闸门，行还在却匹配不到，说明某个闸门在"读取—写入"之间失效了。
     * 回读最新行给出具体原因；若回读发现目标状态其实已经达成，则不抛异常
     * （驱动在 {@code useAffectedRows=true} 时会把"所有字段都没变化"报成 0 行）。</p>
     */
    private void resolveWriteConflict(Long departureId, DepartureUpsertRequest request, boolean rebinding) {
        Departure latest = departureMapper.selectById(departureId);
        if (latest == null) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "团期不存在");
        }
        int occupied = DepartureView.occupiedSeats(latest);
        if (request.maxPeople() < occupied) {
            throw new BusinessException(409, "DEPARTURE_CAPACITY_CONFLICT", capacityMessage(occupied));
        }
        if (rebinding && !DepartureStatus.DRAFT.equals(latest.status)) {
            throw new BusinessException(409, "DEPARTURE_STATE_CONFLICT",
                    "团期已被并发上架，不能改挂线路；请先下架为草稿后重试");
        }
        if (rebinding && !Objects.equals(latest.routeId, request.routeId())) {
            throw new BusinessException(409, "DEPARTURE_STATE_CONFLICT", "团期在本次修改期间被并发改动，请重试");
        }
    }

    private static String capacityMessage(int occupied) {
        return "最大人数不能小于已占用的 " + occupied + " 人，如需缩减请先处理相关订单";
    }

    /**
     * 后台直接改写团期状态，对齐契约 PATCH /admin/departures/{departureId}/status
     * （200 + 更新后的团期）。
     *
     * <p>后台允许把团期改成任意合法状态（如人工下架、取消），因此这里只校验状态取值，
     * 不做状态迁移合法性约束；导游端的 {@link #start(Long)} / {@link #complete(Long)}
     * 才带状态机校验。状态写入与订单级联（行程中 → 在途、已完成 → 完成）同处一个事务。</p>
     *
     * <p>返回更新后的契约视图：此前该端点返回 {@code data: null}，前端只能本地猜测新状态，
     * 也拿不到级联之后的名额与时间戳变化。</p>
     */
    @Transactional
    public DepartureView changeStatus(Long departureId, String status, Long operatorId) {
        Departure departure = requireDeparture(departureId);
        if (status == null || !ALL_STATUSES.contains(status)) {
            throw new BusinessException(422, "VALIDATION_ERROR", STATUS_MESSAGE);
        }
        // 后台没有前置状态限制，因此只按 id 定位；写入与订单级联同处一个事务。
        writeStatus(departureId, status, null);
        cascadeOrderStatus(departureId, status);
        // 状态没变就不写日志：重复点"关闭报名"不该在操作日志里刷出一串无意义记录。
        if (!Objects.equals(departure.status, status)) {
            operationLog.record(operatorId, "团期", "STATUS", "DEPARTURE", departureId,
                    "团期状态由 " + departure.status + " 变更为 " + status);
        }
        return detail(departureId);
    }

    /**
     * 字段语义校验兜底。
     *
     * <p>请求在 Controller 已通过 Bean Validation；这里再查一遍是为了防止绕过请求校验的调用方
     * （直接调用 Service 的代码或测试）写入数据库无法表达的取值，并保证同一类错误
     * 无论从哪条路径进来都是 422 + {@code VALIDATION_ERROR}。</p>
     */
    private static void validateEditableFields(DepartureUpsertRequest request) {
        if (request.startDate() == null || request.endDate() == null
                || request.startDate().isAfter(request.endDate())) {
            throw new BusinessException(422, "VALIDATION_ERROR", "返程日期不能早于出发日期");
        }
        if (request.maxPeople() == null || request.maxPeople() < 1) {
            throw new BusinessException(422, "VALIDATION_ERROR", "最大人数必须大于 0");
        }
        if (request.adultPrice() == null || request.adultPrice().signum() < 0
                || request.childPrice() == null || request.childPrice().signum() < 0) {
            throw new BusinessException(422, "VALIDATION_ERROR", "团期价格不能为负数");
        }
    }

    /** 契约 {@code DepartureUpsertRequest} 允许客户端提交的字段；状态与名额计数不在此列。 */
    private static void applyEditableFields(Departure departure, DepartureUpsertRequest request) {
        departure.routeId = request.routeId();
        departure.startDate = request.startDate();
        departure.endDate = request.endDate();
        departure.adultPrice = request.adultPrice();
        departure.childPrice = request.childPrice();
        departure.maxPeople = request.maxPeople();
        departure.guideId = request.guideId();
    }

    /** 引用了不存在的线路属于字段语义不成立，按契约返回 422（而不是外键报错后的 500）。 */
    private void requireRoute(Long routeId) {
        if (routeId == null || routeMapper.selectById(routeId) == null) {
            throw new BusinessException(422, "VALIDATION_ERROR", "指定的线路不存在");
        }
    }

    private void requireGuide(Long guideId) {
        if (guideId != null && guideMapper.selectById(guideId) == null) {
            throw new BusinessException(422, "VALIDATION_ERROR", "指定的导游不存在");
        }
    }

    private long orderCount(Long departureId) {
        return orderMapper.selectCount(new QueryWrapper<TravelOrder>().eq("departure_id", departureId));
    }

    /**
     * 导游开始行程，对齐契约 POST /guide/departures/{departureId}/start：
     * 只允许把"已开售但尚未出发"的团期推进到 {@link DepartureStatus#TRAVELLING}。
     *
     * <p>草稿（还没上架）、已取消、已在行程中、已完成的团期一律返回 409，
     * 避免状态被反复改写（契约 start/complete 都声明了 409 冲突语义）。</p>
     */
    @Transactional
    public DepartureView start(Long departureId) {
        return transition(departureId, STARTABLE_STATUSES, DepartureStatus.TRAVELLING,
                "当前团期状态不允许开始行程：");
    }

    /**
     * 导游结束行程，对齐契约 POST /guide/departures/{departureId}/complete：
     * 只允许从 {@link DepartureStatus#TRAVELLING} 推进到 {@link DepartureStatus#FINISHED}。
     */
    @Transactional
    public DepartureView complete(Long departureId) {
        return transition(departureId, List.of(DepartureStatus.TRAVELLING), DepartureStatus.FINISHED,
                "只有行程中的团期可以标记为已完成，当前状态：");
    }

    /**
     * 团期状态机迁移：把前置状态校验下推到 UPDATE 语句里，用影响行数做原子闸门。
     *
     * <p>原先的"先 select 判状态、再 {@code updateById} 写状态"只能拦住顺序重复调用：
     * 两个并发请求会同时读到 OPEN（或 TRAVELLING），随后双双更新成功并各执行一次订单级联，
     * 完成团期时还会重复覆盖 {@code completed_at}。改为条件更新
     * {@code UPDATE departure SET status = ? WHERE id = ? AND status IN (允许的旧状态)} 后，
     * 行锁保证只有一个请求能把状态从旧值改走、影响行数为 1；其余请求影响行数为 0，
     * 统一返回 409 {@code DEPARTURE_STATE_CONFLICT}，因此只有迁移成功的那个请求才会继续级联订单。
     * 归属校验（404/403，语义上要求区分"团期不存在"与"不是本人负责"）仍由调用方在进入本方法前完成。</p>
     */
    private DepartureView transition(Long departureId, List<String> fromStatuses, String toStatus,
                                     String conflictMessage) {
        Departure departure = requireDeparture(departureId);
        if (writeStatus(departureId, toStatus, fromStatuses) == 0) {
            // 影响行数为 0：状态已被并发的另一次迁移改走，回读最新状态用于说明冲突原因。
            Departure latest = departureMapper.selectById(departureId);
            String current = latest == null ? departure.status : latest.status;
            throw new BusinessException(409, "DEPARTURE_STATE_CONFLICT", conflictMessage + current);
        }
        cascadeOrderStatus(departureId, toStatus);
        return toView(departureMapper.selectById(departureId));
    }

    /**
     * 只写团期状态，返回影响行数。
     *
     * <p>{@code fromStatuses} 非空时作为 UPDATE 的附加条件（乐观闸门）；为空表示不限制前置状态，
     * 供后台 {@link #changeStatus} 使用。用条件更新而非 {@code updateById} 还会顺带避免
     * 把回读实体里的旧 {@code updated_at} 写回库里——该字段由数据库
     * {@code ON UPDATE CURRENT_TIMESTAMP} 自行维护。</p>
     */
    private int writeStatus(Long departureId, String status, List<String> fromStatuses) {
        UpdateWrapper<Departure> update = new UpdateWrapper<Departure>()
                .eq("id", departureId)
                .set("status", status);
        if (fromStatuses != null && !fromStatuses.isEmpty()) {
            update.in("status", fromStatuses);
        }
        return departureMapper.update(null, update);
    }

    private Departure requireDeparture(Long departureId) {
        Departure departure = departureMapper.selectById(departureId);
        if (departure == null) {
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "团期不存在");
        }
        return departure;
    }

    /** 团期状态变化时同步订单状态：行程中 → 在途；已完成 → 完成（并写入完成时间）。 */
    private void cascadeOrderStatus(Long departureId, String status) {
        if (DepartureStatus.TRAVELLING.equals(status)) {
            orderMapper.update(null, new UpdateWrapper<TravelOrder>()
                    .eq("departure_id", departureId).eq("status", OrderStatus.CONFIRMED)
                    .set("status", OrderStatus.TRAVELLING));
        } else if (DepartureStatus.FINISHED.equals(status)) {
            orderMapper.update(null, new UpdateWrapper<TravelOrder>()
                    .eq("departure_id", departureId)
                    .in("status", OrderStatus.CONFIRMED, OrderStatus.TRAVELLING)
                    .set("status", OrderStatus.COMPLETED).set("completed_at", LocalDateTime.now()));
        }
    }

    private String routeName(Long routeId) {
        if (routeId == null) {
            return null;
        }
        TravelRoute route = routeMapper.selectById(routeId);
        return route == null ? null : route.name;
    }

    private String guideName(Long guideId) {
        if (guideId == null) {
            return null;
        }
        Guide guide = guideMapper.selectById(guideId);
        return guide == null ? null : guide.name;
    }

    private Map<Long, String> routeNameMap(List<Departure> departures) {
        List<Long> ids = distinctIds(departures.stream().map(d -> d.routeId).toList());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return routeMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(r -> r.id, r -> r.name, (a, b) -> a));
    }

    private Map<Long, String> guideNameMap(List<Departure> departures) {
        List<Long> ids = distinctIds(departures.stream().map(d -> d.guideId).toList());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return guideMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(g -> g.id, g -> g.name, (a, b) -> a));
    }

    private static List<Long> distinctIds(Collection<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream().filter(Objects::nonNull).distinct().toList();
    }

    /**
     * 同一导游在同一时间范围内不能带两个团，对齐契约 POST/PUT /admin/departures 声明的 409。
     *
     * <p>返回 409 而不是 400：请求本身合法，冲突来自资源当前状态（该导游已有重叠团期），
     * 运营改派导游或调整日期后即可重试。</p>
     */
    private void checkGuideConflict(Departure departure) {
        if (departure.guideId == null) {
            return;
        }
        QueryWrapper<Departure> query = new QueryWrapper<Departure>()
                .eq("guide_id", departure.guideId)
                .notIn("status", DepartureStatus.CANCELLED, DepartureStatus.FINISHED)
                .le("start_date", departure.endDate)
                .ge("end_date", departure.startDate);
        if (departure.id != null) {
            query.ne("id", departure.id);
        }
        if (departureMapper.selectCount(query) > 0) {
            throw new BusinessException(409, "DEPARTURE_STATE_CONFLICT",
                    "该导游在此时间范围内已有其他团期");
        }
    }
}
