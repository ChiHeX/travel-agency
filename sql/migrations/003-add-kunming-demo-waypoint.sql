-- Add the missing Kunming arrival waypoint to the existing demo route.
-- The coordinate is an approximate city-center marker, not a meeting address.
SET NAMES utf8mb4;
USE travel_agency;

INSERT INTO route_itinerary_item (day_id, sort_no, item_type, name, description, longitude, latitude)
SELECT d.id, 1, 'TRANSPORT', '抵达昆明', '昆明市区示意位置，实际集合地点以出团通知为准。', 102.7120000, 25.0400000
FROM travel_route r
JOIN route_itinerary_day d ON d.route_id = r.id AND d.day_number = 1
WHERE r.name = '彩云之南经典 6 日跟团游'
  AND NOT EXISTS (SELECT 1 FROM route_itinerary_item i WHERE i.day_id = d.id AND i.name = '抵达昆明');
