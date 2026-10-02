-- 地图坐标数据加固（存量库）
--
-- 1) 把"半截坐标"（只填了经度或只填了纬度）统一清成 NULL。这类坐标在地图上无法定位：
--    用户端地图要求两个坐标都非空才落点，只填一个的点会被静默丢弃，运营却以为已经录入。
--    清成 NULL 后，新的写入会按契约的 CoordinatePairRule 被 422 拒绝
--    （AttractionUpsertRequest / HotelCreateRequest / HotelUpdateRequest / ItineraryItemRequest
--    四个请求模型都引用该规则），不会再产生半截坐标；已被清空的记录可以重新成对填写。
--    只清"半截"的行，成对的坐标与两列都为空的记录都不会被改动。
--
-- 2) 为主演示线路「彩云之南经典 6 日跟团游」引用的演示酒店补齐坐标（与 sql/test-data.sql
--    的新档一致），避免后台酒店列表里它长期显示"未填写坐标"。
--
-- 两个步骤都可重复执行：第二次执行时不再匹配任何行。
SET NAMES utf8mb4;
USE travel_agency;

-- 1) 半截坐标规范化
UPDATE attraction SET longitude = NULL WHERE longitude IS NOT NULL AND latitude IS NULL;
UPDATE attraction SET latitude = NULL WHERE latitude IS NOT NULL AND longitude IS NULL;
UPDATE hotel SET longitude = NULL WHERE longitude IS NOT NULL AND latitude IS NULL;
UPDATE hotel SET latitude = NULL WHERE latitude IS NOT NULL AND longitude IS NULL;
UPDATE route_itinerary_item SET longitude = NULL WHERE longitude IS NOT NULL AND latitude IS NULL;
UPDATE route_itinerary_item SET latitude = NULL WHERE latitude IS NOT NULL AND longitude IS NULL;

-- 2) 演示酒店坐标补齐（仅在坐标缺失时写入，坐标为大理古城区示意位置）
UPDATE hotel SET longitude = 100.1650000, latitude = 25.6940000
WHERE name = '彩云之南演示酒店' AND (longitude IS NULL OR latitude IS NULL)
ORDER BY id LIMIT 1;
