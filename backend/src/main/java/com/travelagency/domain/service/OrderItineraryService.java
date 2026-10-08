package com.travelagency.domain.service;

import com.travelagency.common.enums.OrderStatus;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.ItineraryDayView;
import com.travelagency.domain.entity.TravelOrder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class OrderItineraryService {
    private final OrderService orders;
    private final AdminRouteService routes;

    public OrderItineraryService(OrderService orders, AdminRouteService routes) {
        this.orders = orders;
        this.routes = routes;
    }

    public List<ItineraryDayView> itinerary(String orderNo, Long userId) {
        TravelOrder order = orders.findByNo(orderNo);
        if (!Objects.equals(order.userId, userId)) {
            throw new BusinessException(403, "ACCESS_DENIED", "无权查看该订单的行程");
        }
        if (!List.of(OrderStatus.CONFIRMED, OrderStatus.TRAVELLING, OrderStatus.COMPLETED).contains(order.status)) {
            throw new BusinessException(409, "ORDER_STATE_CONFLICT", "报名确认后才能查看每日行程");
        }
        return routes.itineraryDays(order.routeId);
    }
}
