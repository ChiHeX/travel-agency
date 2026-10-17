package com.travelagency.domain.service;

import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.entity.TravelOrder;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderItineraryServiceTest {
    private final OrderService orders = mock(OrderService.class);
    private final AdminRouteService routes = mock(AdminRouteService.class);
    private final OrderItineraryService service = new OrderItineraryService(orders, routes);

    private void order(String status) {
        TravelOrder order = new TravelOrder();
        order.userId = 1L;
        order.routeId = 2L;
        order.status = status;
        when(orders.findByNo("TEST")).thenReturn(order);
    }

    @Test
    void readsCurrentItineraryWithoutPublicRouteStatusRestriction() {
        when(routes.itineraryDays(2L)).thenReturn(List.of());
        for (String status : List.of("CONFIRMED", "TRAVELLING", "COMPLETED")) {
            order(status);
            assertEquals(List.of(), service.itinerary("TEST", 1L));
        }
        verify(routes, times(3)).itineraryDays(2L);
    }

    @Test
    void rejectsOtherOwnersBeforeReadingItinerary() {
        order("CONFIRMED");
        BusinessException error = assertThrows(BusinessException.class, () -> service.itinerary("TEST", 99L));
        assertEquals(403, error.getStatus());
        assertEquals("ACCESS_DENIED", error.getCode());
        verifyNoInteractions(routes);
    }

    @Test
    void rejectsIneligibleStates() {
        for (String status : List.of("WAIT_PAY", "PAID_WAIT_CONFIRM", "CANCELLED", "REFUND_APPLYING", "REFUNDED")) {
            order(status);
            BusinessException error = assertThrows(BusinessException.class, () -> service.itinerary("TEST", 1L));
            assertEquals(409, error.getStatus());
            assertEquals("ORDER_STATE_CONFLICT", error.getCode());
        }
        verifyNoInteractions(routes);
    }

    @Test
    void missingOrderRemainsNotFound() {
        when(orders.findByNo("MISSING")).thenThrow(new BusinessException(404, "RESOURCE_NOT_FOUND", "订单不存在"));
        assertEquals(404, assertThrows(BusinessException.class, () -> service.itinerary("MISSING", 1L)).getStatus());
        verifyNoInteractions(routes);
    }
}
