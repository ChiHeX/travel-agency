package com.travelagency.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travelagency.common.api.ApiResponse;
import com.travelagency.common.enums.DepartureStatus;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.common.security.CurrentUser;
import com.travelagency.domain.dto.StatusRequest;
import com.travelagency.domain.entity.Departure;
import com.travelagency.domain.entity.Guide;
import com.travelagency.domain.entity.OrderTraveler;
import com.travelagency.domain.entity.TravelOrder;
import com.travelagency.domain.mapper.DepartureMapper;
import com.travelagency.domain.mapper.GuideMapper;
import com.travelagency.domain.mapper.OrderTravelerMapper;
import com.travelagency.domain.mapper.TravelOrderMapper;
import com.travelagency.domain.service.AdminRouteService;
import com.travelagency.domain.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/guide")
@PreAuthorize("hasAnyRole('GUIDE','ADMIN')")
public class GuideController {

    private final GuideMapper guideMapper;
    private final DepartureMapper departureMapper;
    private final TravelOrderMapper orderMapper;
    private final OrderTravelerMapper orderTravelerMapper;
    private final AdminRouteService adminRouteService;
    private final com.travelagency.domain.service.DepartureService departureService;

    public GuideController(
            GuideMapper guideMapper,
            DepartureMapper departureMapper,
            TravelOrderMapper orderMapper,
            OrderTravelerMapper orderTravelerMapper,
            AdminRouteService adminRouteService,
            com.travelagency.domain.service.DepartureService departureService) {
        this.guideMapper = guideMapper;
        this.departureMapper = departureMapper;
        this.orderMapper = orderMapper;
        this.orderTravelerMapper = orderTravelerMapper;
        this.adminRouteService = adminRouteService;
        this.departureService = departureService;
    }

    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard() {
        Guide guide = currentGuide();
        List<Departure> departures = departureMapper.selectList(new QueryWrapper<Departure>()
                .eq("guide_id", guide.id).orderByAsc("start_date"));
        return ApiResponse.ok(Map.of(
                "upcoming", departures.stream().filter(departure -> departure.startDate != null).toList(),
                "active", departures.stream().filter(departure -> DepartureStatus.TRAVELLING.equals(departure.status)).toList(),
                "history", departures.stream().filter(departure -> DepartureStatus.FINISHED.equals(departure.status)).toList()));
    }

    @GetMapping("/departures")
    public ApiResponse<List<Departure>> departures(@RequestParam(required = false) String status) {
        Guide guide = currentGuide();
        QueryWrapper<Departure> query = new QueryWrapper<Departure>().eq("guide_id", guide.id);
        if (status != null && !status.isBlank()) {
            query.eq("status", status);
        }
        return ApiResponse.ok(departureMapper.selectList(query.orderByAsc("start_date")));
    }

    @GetMapping("/departures/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        Departure departure = ownedDeparture(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("departure", departure);
        result.put("route", adminRouteService.routeView(departure.routeId));
        result.put("passengers", passengerList(id));
        return ApiResponse.ok(result);
    }

    @GetMapping("/departures/{id}/passengers")
    public ApiResponse<List<Map<String, Object>>> passengers(@PathVariable Long id) {
        ownedDeparture(id);
        return ApiResponse.ok(passengerList(id));
    }

    @PatchMapping("/departures/{id}/status")
    public ApiResponse<Void> status(@PathVariable Long id, @Valid @org.springframework.web.bind.annotation.RequestBody StatusRequest request) {
        ownedDeparture(id);
        if (!DepartureStatus.TRAVELLING.equals(request.status()) && !DepartureStatus.FINISHED.equals(request.status())) {
            throw new BusinessException("导游只能更新行程中或已完成状态");
        }
        departureService.changeStatus(id, request.status());
        return ApiResponse.ok();
    }

    private Guide currentGuide() {
        Guide guide = guideMapper.selectOne(new QueryWrapper<Guide>().eq("user_id", CurrentUser.required().userId()));
        if (guide == null) {
            throw new BusinessException(403, "当前账号未绑定导游资料");
        }
        return guide;
    }

    private Departure ownedDeparture(Long id) {
        Departure departure = departureMapper.selectById(id);
        if (departure == null || !Objects.equals(departure.guideId, currentGuide().id)) {
            throw new BusinessException(403, "只能查看自己负责的团期");
        }
        return departure;
    }

    private List<Map<String, Object>> passengerList(Long departureId) {
        return orderMapper.selectList(new QueryWrapper<TravelOrder>()
                        .eq("departure_id", departureId)
                        .notIn("status", "WAIT_PAY", "CANCELLED", "REFUNDED"))
                .stream().flatMap(order -> orderTravelerMapper.selectList(new QueryWrapper<OrderTraveler>()
                                .eq("order_id", order.id).orderByAsc("id"))
                        .stream().map(traveler -> passenger(order, traveler)))
                .toList();
    }

    private Map<String, Object> passenger(TravelOrder order, OrderTraveler traveler) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderNo", order.orderNo);
        result.put("name", traveler.name);
        result.put("phone", traveler.phone);
        result.put("emergencyName", traveler.emergencyName);
        result.put("emergencyPhone", traveler.emergencyPhone);
        result.put("idNo", OrderService.maskId(traveler.idNo));
        return result;
    }
}
