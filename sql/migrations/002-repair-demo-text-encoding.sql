-- Repair only exact, known demo strings imported as latin1 instead of UTF-8.
-- Back up the affected content tables before applying. No IDs or order snapshots change.
SET NAMES utf8mb4;
USE travel_agency;
CREATE TEMPORARY TABLE demo_text_encoding_fix (
    correct_text VARCHAR(1000) CHARACTER SET utf8mb4 NOT NULL,
    broken_text VARBINARY(4000) NOT NULL
);
INSERT INTO demo_text_encoding_fix (correct_text, broken_text)
SELECT text_value, CAST(CONVERT(CONVERT(CAST(text_value AS BINARY) USING latin1) USING utf8mb4) AS BINARY)
FROM (
    SELECT _utf8mb4'注册用户' AS text_value
    UNION ALL SELECT _utf8mb4'浏览、报名、订单与评价'
    UNION ALL SELECT _utf8mb4'旅行社工作人员'
    UNION ALL SELECT _utf8mb4'线路、团期、订单与退款运营'
    UNION ALL SELECT _utf8mb4'导游'
    UNION ALL SELECT _utf8mb4'仅查看和执行本人负责的团期'
    UNION ALL SELECT _utf8mb4'系统管理员'
    UNION ALL SELECT _utf8mb4'系统级用户与权限管理'
    UNION ALL SELECT _utf8mb4'演示管理员'
    UNION ALL SELECT _utf8mb4'大理古城'
    UNION ALL SELECT _utf8mb4'大理'
    UNION ALL SELECT _utf8mb4'云南省大理市大理古城'
    UNION ALL SELECT _utf8mb4'示例景点资料，用于地图与行程演示。'
    UNION ALL SELECT _utf8mb4'团队整理的测试数据；坐标仅用于软件演示'
    UNION ALL SELECT _utf8mb4'丽江古城'
    UNION ALL SELECT _utf8mb4'丽江'
    UNION ALL SELECT _utf8mb4'云南省丽江市古城区'
    UNION ALL SELECT _utf8mb4'彩云之南经典 6 日跟团游'
    UNION ALL SELECT _utf8mb4'上海'
    UNION ALL SELECT _utf8mb4'昆明·大理·丽江'
    UNION ALL SELECT _utf8mb4'示例线路，用于演示线路、行程、团期和订单业务闭环。'
    UNION ALL SELECT _utf8mb4'交通、住宿、行程内景点首道门票'
    UNION ALL SELECT _utf8mb4'个人消费、行程外活动、单房差'
    UNION ALL SELECT _utf8mb4'请在出发前确认有效证件与紧急联系人信息。'
    UNION ALL SELECT _utf8mb4'彩云之南演示酒店'
    UNION ALL SELECT _utf8mb4'云南省大理市古城区'
    UNION ALL SELECT _utf8mb4'课程演示用酒店资料，不提供独立预订。'
    UNION ALL SELECT _utf8mb4'团队原创测试资料'
    UNION ALL SELECT _utf8mb4'上海 · 昆明'
    UNION ALL SELECT _utf8mb4'抵达昆明，完成集合与入住。'
    UNION ALL SELECT _utf8mb4'飞机 / 大巴'
    UNION ALL SELECT _utf8mb4'晚餐自理'
    UNION ALL SELECT _utf8mb4'昆明 · 大理'
    UNION ALL SELECT _utf8mb4'前往大理古城，感受苍山洱海风光。'
    UNION ALL SELECT _utf8mb4'旅游大巴'
    UNION ALL SELECT _utf8mb4'早、午餐'
    UNION ALL SELECT _utf8mb4'大理 · 丽江'
    UNION ALL SELECT _utf8mb4'游览古城，前往丽江。'
    UNION ALL SELECT _utf8mb4'古城步行游览。'
    UNION ALL SELECT _utf8mb4'演示景点与线路基础资料'
    UNION ALL SELECT _utf8mb4'团队原创整理的课程测试数据'
    UNION ALL SELECT _utf8mb4'仅限课程项目开发、测试与答辩演示'
    UNION ALL SELECT _utf8mb4'不代表真实旅行社经营数据'
) AS originals;
START TRANSACTION;
UPDATE travel_route AS target JOIN demo_text_encoding_fix AS fix
    ON CAST(target.name AS BINARY) = fix.broken_text
SET target.name = fix.correct_text;
UPDATE travel_route AS target JOIN demo_text_encoding_fix AS fix
    ON CAST(target.departure_city AS BINARY) = fix.broken_text
SET target.departure_city = fix.correct_text;
UPDATE travel_route AS target JOIN demo_text_encoding_fix AS fix
    ON CAST(target.destination AS BINARY) = fix.broken_text
SET target.destination = fix.correct_text;
UPDATE travel_route AS target JOIN demo_text_encoding_fix AS fix
    ON CAST(target.description AS BINARY) = fix.broken_text
SET target.description = fix.correct_text;
UPDATE travel_route AS target JOIN demo_text_encoding_fix AS fix
    ON CAST(target.included AS BINARY) = fix.broken_text
SET target.included = fix.correct_text;
UPDATE travel_route AS target JOIN demo_text_encoding_fix AS fix
    ON CAST(target.excluded AS BINARY) = fix.broken_text
SET target.excluded = fix.correct_text;
UPDATE travel_route AS target JOIN demo_text_encoding_fix AS fix
    ON CAST(target.booking_notice AS BINARY) = fix.broken_text
SET target.booking_notice = fix.correct_text;
UPDATE attraction AS target JOIN demo_text_encoding_fix AS fix
    ON CAST(target.name AS BINARY) = fix.broken_text
SET target.name = fix.correct_text;
UPDATE attraction AS target JOIN demo_text_encoding_fix AS fix
    ON CAST(target.city AS BINARY) = fix.broken_text
SET target.city = fix.correct_text;
UPDATE attraction AS target JOIN demo_text_encoding_fix AS fix
    ON CAST(target.address AS BINARY) = fix.broken_text
SET target.address = fix.correct_text;
UPDATE attraction AS target JOIN demo_text_encoding_fix AS fix
    ON CAST(target.intro AS BINARY) = fix.broken_text
SET target.intro = fix.correct_text;
UPDATE attraction AS target JOIN demo_text_encoding_fix AS fix
    ON CAST(target.data_source AS BINARY) = fix.broken_text
SET target.data_source = fix.correct_text;
UPDATE hotel AS target JOIN demo_text_encoding_fix AS fix
    ON CAST(target.name AS BINARY) = fix.broken_text
SET target.name = fix.correct_text;
UPDATE hotel AS target JOIN demo_text_encoding_fix AS fix
    ON CAST(target.address AS BINARY) = fix.broken_text
SET target.address = fix.correct_text;
UPDATE hotel AS target JOIN demo_text_encoding_fix AS fix
    ON CAST(target.intro AS BINARY) = fix.broken_text
SET target.intro = fix.correct_text;
UPDATE hotel AS target JOIN demo_text_encoding_fix AS fix
    ON CAST(target.data_source AS BINARY) = fix.broken_text
SET target.data_source = fix.correct_text;
UPDATE route_itinerary_day AS target JOIN demo_text_encoding_fix AS fix
    ON CAST(target.title AS BINARY) = fix.broken_text
SET target.title = fix.correct_text;
UPDATE route_itinerary_day AS target JOIN demo_text_encoding_fix AS fix
    ON CAST(target.description AS BINARY) = fix.broken_text
SET target.description = fix.correct_text;
UPDATE route_itinerary_day AS target JOIN demo_text_encoding_fix AS fix
    ON CAST(target.transportation AS BINARY) = fix.broken_text
SET target.transportation = fix.correct_text;
UPDATE route_itinerary_day AS target JOIN demo_text_encoding_fix AS fix
    ON CAST(target.meals AS BINARY) = fix.broken_text
SET target.meals = fix.correct_text;
UPDATE route_itinerary_item AS target JOIN demo_text_encoding_fix AS fix
    ON CAST(target.name AS BINARY) = fix.broken_text
SET target.name = fix.correct_text;
UPDATE route_itinerary_item AS target JOIN demo_text_encoding_fix AS fix
    ON CAST(target.description AS BINARY) = fix.broken_text
SET target.description = fix.correct_text;
COMMIT;
DROP TEMPORARY TABLE demo_text_encoding_fix;
