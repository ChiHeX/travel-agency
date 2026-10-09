-- 013-align-valid-booking-count.sql
-- 对齐存量库的 travel_route.valid_booking_count，使其等于真实「有效报名」订单条数。
--
-- 背景：该列是反范式的物化计数，由业务链路维护（确认报名 +1 / 退款完成 -1）。
-- 早期演示数据在 test-data.sql 中把它预置成与真实订单无关的大数（42/81/106…），
-- 于是「热门线路」等按该列排序的展示长期偏离真实订单，且不会自动收敛
-- （PRD §27 要求热门线路按有效报名订单统计）。
--
-- 现在热门线路已改为直接聚合 travel_order 实时统计（见 docs/API.md §12.5、
-- docs/DATABASE_DESIGN.md §6.1），不再依赖该列；本迁移负责把存量库里已被污染的
-- 列一次性对齐，使仍按该列排序/展示的其它入口（例如公开线路列表的「报名人数」排序）
-- 也回到真实数据。全新库由 test-data.sql 在导入订单后自行重算，无需执行本脚本。
--
-- 口径必须与 TravelRouteMapper#popularRouteCounts / #popularDestinations 完全一致：
--   已确认（含出行中 / 已完成），或退款申请中但由已确认发起的订单。
--   - 退款申请提交后、退款完成之前名额与计数都还没回退，这些订单仍属有效报名；
--   - PAID_WAIT_CONFIRM 的订单也能申请退款，但它从未 +1 过，必须用退款单上的
--     original_order_status 区分，不能算进来。
--
-- 幂等：UPDATE 取的是订单聚合结果，重复执行结果相同。执行两次安全。

UPDATE travel_route r
SET r.valid_booking_count = (
    SELECT COUNT(*)
    FROM travel_order o
    WHERE o.route_id = r.id
      AND (o.status IN ('CONFIRMED', 'TRAVELLING', 'COMPLETED')
           OR EXISTS (SELECT 1 FROM refund f
                      WHERE f.order_id = o.id
                        AND f.status IN ('APPLYING', 'PROCESSING')
                        AND f.original_order_status IN ('CONFIRMED', 'TRAVELLING')))
)
WHERE r.deleted = 0;
