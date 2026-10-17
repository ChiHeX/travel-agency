package com.travelagency;

import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.dto.RefundRequest;
import com.travelagency.domain.service.LocalPaymentService;
import com.travelagency.domain.service.OrderService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"app.jwt.secret=local-payment-integration-test-secret-32-characters", "server.address=127.0.0.1"})
@ActiveProfiles("local-payment")
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class LocalPaymentIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired LocalPaymentService localPayments;
    @Autowired OrderService orders;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    private Long userId, routeId, departureId, orderId;
    private String orderNo;

    @BeforeEach
    void createFixtures() {
        orderNo = "LP" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        jdbc.update("INSERT INTO sys_user(username,password_hash,nickname) VALUES (?, 'test-only', '模拟付款测试')", orderNo);
        userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, orderNo);
        jdbc.update("INSERT INTO travel_route(name,departure_city,destination,duration_days,status) VALUES (?, '上海', '杭州', 1, 'PUBLISHED')", orderNo);
        routeId = jdbc.queryForObject("SELECT id FROM travel_route WHERE name=?", Long.class, orderNo);
        jdbc.update("INSERT INTO departure(route_id,start_date,end_date,adult_price,child_price,max_people,reserved_people,confirmed_people,status) VALUES (?, CURRENT_DATE + INTERVAL 20 DAY, CURRENT_DATE + INTERVAL 21 DAY, 123.45, 0, 10, 1, 0, 'OPEN')", routeId);
        departureId = jdbc.queryForObject("SELECT id FROM departure WHERE route_id=?", Long.class, routeId);
        jdbc.update("INSERT INTO travel_order(order_no,user_id,route_id,departure_id,contact_name,contact_phone,adult_count,child_count,adult_unit_price,child_unit_price,total_amount,status,payment_status) VALUES (?,?,?,?,'测试游客','13800000000',1,0,123.45,0,123.45,'WAIT_PAY','UNPAID')", orderNo, userId, routeId, departureId);
        orderId = jdbc.queryForObject("SELECT id FROM travel_order WHERE order_no=?", Long.class, orderNo);
        jdbc.update("INSERT INTO payment(order_id,payment_no,channel,amount,status) VALUES (?,?,'ALIPAY_SANDBOX',123.45,'UNPAID')", orderId, orderNo);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM sys_message WHERE user_id=?", userId);
        jdbc.update("DELETE FROM payment WHERE order_id=?", orderId);
        jdbc.update("DELETE FROM travel_order WHERE id=?", orderId);
        jdbc.update("DELETE FROM departure WHERE id=?", departureId);
        jdbc.update("DELETE FROM travel_route WHERE id=?", routeId);
        jdbc.update("DELETE FROM sys_user WHERE id=?", userId);
    }

    @Test
    void httpEndpointsRequireAuthenticationAndOwnershipAndSerializePayment() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/payments/local/" + orderNo))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/payments/options"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        var owner = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(
                new com.travelagency.common.security.UserPrincipal(userId, orderNo, java.util.List.of("USER"), true));
        var stranger = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(
                new com.travelagency.common.security.UserPrincipal(userId + 100000, "other", java.util.List.of("USER"), true));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/payments/options").with(owner))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.localSimulationEnabled").value(true));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/payments/local/" + orderNo).with(stranger))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/payments/local/" + orderNo).with(owner))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.status").value("PAID"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.channel").value("LOCAL_SIMULATION"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.amount").value("123.45"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.id").isString());
    }

    @Test
    void concurrentRetriesCommitOnePaymentAndOneNotificationWithoutChangingCapacity() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 4; i++) futures.add(executor.submit(() -> {
                start.await();
                assertEquals("PAID", localPayments.pay(orderNo, userId).status());
                return null;
            }));
            start.countDown();
            for (var future : futures) future.get(30, TimeUnit.SECONDS);
        }
        assertEquals("PAID_WAIT_CONFIRM", jdbc.queryForObject("SELECT status FROM travel_order WHERE id=?", String.class, orderId));
        assertEquals("LOCAL_SIMULATION", jdbc.queryForObject("SELECT channel FROM payment WHERE order_id=?", String.class, orderId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_message WHERE user_id=? AND type='PAYMENT_SUCCESS'", Integer.class, userId));
        assertEquals(1, jdbc.queryForObject("SELECT reserved_people FROM departure WHERE id=?", Integer.class, departureId));
        assertEquals("LOCAL_PAYMENT_REFUND_UNSUPPORTED", assertThrows(BusinessException.class,
                () -> orders.applyRefund(orderNo, userId, new RefundRequest("测试退款"))).getCode());
    }

    @Test
    void cancellationAndPaymentCannotBothSucceed() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var pay = executor.submit(() -> race(start, () -> localPayments.pay(orderNo, userId)));
            var cancel = executor.submit(() -> race(start, () -> orders.cancel(orderNo, userId)));
            start.countDown();
            assertNotEquals(pay.get(30, TimeUnit.SECONDS), cancel.get(30, TimeUnit.SECONDS));
        }
        String status = jdbc.queryForObject("SELECT status FROM travel_order WHERE id=?", String.class, orderId);
        boolean paid = "PAID_WAIT_CONFIRM".equals(status);
        assertTrue(paid || "CANCELLED".equals(status));
        assertEquals(paid ? "PAID" : "UNPAID", jdbc.queryForObject("SELECT status FROM payment WHERE order_id=?", String.class, orderId));
        assertEquals(paid ? 1 : 0, jdbc.queryForObject("SELECT reserved_people FROM departure WHERE id=?", Integer.class, departureId));
    }

    private boolean race(CountDownLatch start, Runnable action) throws InterruptedException {
        start.await();
        try { action.run(); return true; }
        catch (BusinessException expected) { assertEquals(409, expected.getStatus()); return false; }
    }
}
