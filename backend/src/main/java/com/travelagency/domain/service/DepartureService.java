package com.travelagency.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelagency.common.api.PageResponse;
import com.travelagency.common.enums.DepartureStatus;
import com.travelagency.common.enums.OrderStatus;
import com.travelagency.common.exception.BusinessException;
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

    public DepartureService(DepartureMapper departureMapper, TravelOrderMapper orderMapper,
                            TravelRouteMapper routeMapper, GuideMapper guideMapper) {
        this.departureMapper = departureMapper;
        this.orderMapper = orderMapper;
        this.routeMapper = routeMapper;
        this.guideMapper = guideMapper;
    }

    /**
     * 后台团期分页查询，对齐契约 GET /admin/departures：
     * 返回分页信封而非裸数组，items 为契约 Departure 视图（含 availableSeats / routeName / guideName）。
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
            query.eq("status", status);
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

    @Transactional
    public Departure save(Departure departure) {
        if (departure.startDate == null || departure.endDate == null || departure.endDate.isBefore(departure.startDate)) {
            throw new BusinessException("团期日期不合法");
        }
        if (departure.maxPeople == null || departure.maxPeople <= 0) {
            throw new BusinessException("最大人数必须大于 0");
        }
        if (departure.adultPrice == null || departure.adultPrice.signum() < 0
                || departure.childPrice == null || departure.childPrice.signum() < 0) {
            throw new BusinessException("团期价格不能为负数");
        }
        if (departure.status == null || departure.status.isBlank()) {
            departure.status = DepartureStatus.DRAFT;
        }
        if (departure.reservedPeople == null) {
            departure.reservedPeople = 0;
        }
        if (departure.confirmedPeople == null) {
            departure.confirmedPeople = 0;
        }
        checkGuideConflict(departure);
        if (departure.id == null) {
            departure.version = 0;
            departureMapper.insert(departure);
        } else {
            departureMapper.updateById(departure);
        }
        return departure;
    }

    @Transactional
    public void changeStatus(Long departureId, String status) {
        Departure departure = departureMapper.selectById(departureId);
        if (departure == null) {
            throw new BusinessException(404, "团期不存在");
        }
        if (!List.of(DepartureStatus.DRAFT, DepartureStatus.OPEN, DepartureStatus.FULL,
                DepartureStatus.CLOSED, DepartureStatus.TRAVELLING, DepartureStatus.FINISHED,
                DepartureStatus.CANCELLED).contains(status)) {
            throw new BusinessException("团期状态不合法");
        }
        departure.status = status;
        departureMapper.updateById(departure);
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
            throw new BusinessException("该导游在此时间范围内已有其他团期");
        }
    }
}
