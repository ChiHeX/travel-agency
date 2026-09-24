-- Add one published demo tour with exactly one geocoded itinerary location.
SET NAMES utf8mb4;
USE travel_agency;

SET @single_attraction_id = (SELECT id FROM attraction WHERE name = '大理古城' AND city = '大理' ORDER BY id LIMIT 1);
INSERT INTO travel_route (name, departure_city, destination, duration_days, description, included, excluded, booking_notice, status)
SELECT '大理古城单地点演示团', '大理', '大理', 1,
       '课程测试线路：仅包含大理古城一个行程地点，用于验证单点地图展示。',
       '大理古城导览服务', '交通、餐食及个人消费', '课程演示数据，不代表实际旅行社产品。', 'PUBLISHED'
WHERE @single_attraction_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM travel_route WHERE name = '大理古城单地点演示团' AND deleted = 0);
SET @single_route_id = (SELECT id FROM travel_route WHERE name = '大理古城单地点演示团' AND deleted = 0 ORDER BY id LIMIT 1);

INSERT INTO route_itinerary_day (route_id, day_number, title, description, transportation, meals)
SELECT @single_route_id, 1, '大理古城一日游', '在大理古城游览，行程地图仅标记此处。', '步行', '自理'
WHERE @single_route_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM route_itinerary_day WHERE route_id = @single_route_id AND day_number = 1);
SET @single_day_id = (SELECT id FROM route_itinerary_day WHERE route_id = @single_route_id AND day_number = 1 LIMIT 1);

INSERT INTO route_itinerary_item (day_id, sort_no, item_type, name, description, attraction_id, longitude, latitude)
SELECT @single_day_id, 1, 'ATTRACTION', '大理古城', '单地点地图展示用演示行程。', a.id, a.longitude, a.latitude
FROM attraction a WHERE a.id = @single_attraction_id AND @single_day_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM route_itinerary_item WHERE day_id = @single_day_id AND sort_no = 1);

INSERT INTO departure (route_id, start_date, end_date, adult_price, child_price, max_people, status)
SELECT @single_route_id, DATE_ADD(CURRENT_DATE, INTERVAL 14 DAY), DATE_ADD(CURRENT_DATE, INTERVAL 14 DAY), 299.00, 199.00, 20, 'OPEN'
WHERE @single_route_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM departure WHERE route_id = @single_route_id AND start_date = DATE_ADD(CURRENT_DATE, INTERVAL 14 DAY));
