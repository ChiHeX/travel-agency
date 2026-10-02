-- 地图坐标数据加固（存量库）
--
-- 只做一件事：把"半截坐标"（只填了经度或只填了纬度）统一清成 NULL。
-- 这类坐标在用户端地图上无法定位：地图要求两个坐标都非空才落点，只填一个的点会被静默丢弃，
-- 运营却以为已经录入。清成 NULL 后，新的写入会按契约的 CoordinatePairRule 被 422 拒绝
-- （AttractionUpsertRequest / HotelCreateRequest / HotelUpdateRequest / ItineraryItemRequest
-- 四个请求模型都引用该规则），不会再产生半截坐标；已被清空的记录可以重新成对填写。
-- 只清"半截"的行：成对的坐标与两列都为空的记录都不会被改动。
--
-- 本脚本不写入任何坐标值。演示酒店的坐标定义在种子数据 sql/test-data.sql 里，但那份脚本对
-- 已存在的同名酒店是整条跳过（WHERE NOT EXISTS），重复导入并不能补齐存量酒店的坐标 ——
-- 存量酒店请到后台「酒店合作资料」页补录。
-- 这里曾经"按名称给演示酒店补坐标"，已删除 —— hotel.name 没有唯一约束，同名记录不止一条时
-- 每执行一次就会顺着 ORDER BY id LIMIT 1 补下一条，既不幂等，也无法保证补的是演示线路真正
-- 引用的那家酒店。
--
-- 幂等：每条语句只匹配"半截"的行，执行后这些行两列都为空、不再匹配，第二次执行不修改任何行。
SET NAMES utf8mb4;
USE travel_agency;

-- 1) 景点
UPDATE attraction SET longitude = NULL WHERE longitude IS NOT NULL AND latitude IS NULL;
UPDATE attraction SET latitude = NULL WHERE latitude IS NOT NULL AND longitude IS NULL;

-- 2) 酒店
UPDATE hotel SET longitude = NULL WHERE longitude IS NOT NULL AND latitude IS NULL;
UPDATE hotel SET latitude = NULL WHERE latitude IS NOT NULL AND longitude IS NULL;

-- 3) 行程项目
UPDATE route_itinerary_item SET longitude = NULL WHERE longitude IS NOT NULL AND latitude IS NULL;
UPDATE route_itinerary_item SET latitude = NULL WHERE latitude IS NOT NULL AND longitude IS NULL;
