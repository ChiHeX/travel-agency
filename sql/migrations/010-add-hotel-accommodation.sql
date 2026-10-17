-- 010 酒店展示资料 + 每日行程的住宿安排
--
-- 背景：用户端线路详情的每日行程此前只能显示一句「住宿：酒店名称」。本次补齐两件事：
--   1) 酒店基础资料（城市、封面、详情图片、官方星级、设施、入住退房时间）与 hotel_image 表；
--   2) 每日行程自己的住宿安排（类型 / 标准 / 房型 / 是否含早餐 / 补充说明）。
--
-- 两类数据刻意分开：住宿安排属于**当天行程**（同一条线路的不同天可以安排不同房型、不同早餐），
-- 放在 hotel 基础资料里就会被一家酒店的多次入住共享，改一次全部跟着变。
--
-- 面向存量库；全新库由 sql/schema.sql 直接建表（CONTRIBUTING §12 要求两处同步）。
-- 全部语句可重复执行（MySQL 没有 ADD COLUMN IF NOT EXISTS，按 sql/migrations/README.md 的模板判断）。
--
-- 执行顺序：先补列 / 建表，再回填 accommodation_type。
-- 应用升级前必须先执行本脚本：Hotel / RouteItineraryDay 实体的列清单包含这些新列，
-- 未执行时酒店与每日行程相关接口会报 Unknown column（与 008 同类问题）。

-- 1. hotel 基础资料新增列
SET @ddl := (
    SELECT IF(
        EXISTS(SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'hotel' AND COLUMN_NAME = 'city'),
        'SELECT ''column already exists'' AS skipped',
        -- 存量酒店的 city 补成空串（表示尚未录入城市），不按名称猜城市
        'ALTER TABLE hotel ADD COLUMN city VARCHAR(64) NOT NULL DEFAULT '''' AFTER name'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := (
    SELECT IF(
        EXISTS(SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'hotel' AND COLUMN_NAME = 'cover_url'),
        'SELECT ''column already exists'' AS skipped',
        'ALTER TABLE hotel ADD COLUMN cover_url VARCHAR(500) AFTER contact_phone'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := (
    SELECT IF(
        EXISTS(SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'hotel' AND COLUMN_NAME = 'star_rating'),
        'SELECT ''column already exists'' AS skipped',
        -- 官方星级 1~5；没有可靠依据时保持 NULL，不用网站评分或"几钻"代替
        'ALTER TABLE hotel ADD COLUMN star_rating TINYINT AFTER latitude'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := (
    SELECT IF(
        EXISTS(SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'hotel' AND COLUMN_NAME = 'facilities'),
        'SELECT ''column already exists'' AS skipped',
        -- 契约 HotelFacility 枚举组成的 JSON 数组；JSON 列由数据库保证内容合法
        'ALTER TABLE hotel ADD COLUMN facilities JSON AFTER intro'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := (
    SELECT IF(
        EXISTS(SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'hotel' AND COLUMN_NAME = 'check_in_time'),
        'SELECT ''column already exists'' AS skipped',
        'ALTER TABLE hotel ADD COLUMN check_in_time VARCHAR(5) AFTER facilities'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := (
    SELECT IF(
        EXISTS(SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'hotel' AND COLUMN_NAME = 'check_out_time'),
        'SELECT ''column already exists'' AS skipped',
        'ALTER TABLE hotel ADD COLUMN check_out_time VARCHAR(5) AFTER check_in_time'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 后台酒店列表按城市精确筛选（与 attraction.city 同口径）
SET @ddl := (
    SELECT IF(
        EXISTS(SELECT 1 FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'hotel' AND INDEX_NAME = 'idx_hotel_city'),
        'SELECT ''index already exists'' AS skipped',
        'ALTER TABLE hotel ADD INDEX idx_hotel_city (city)'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 酒店详情图片表
--    只登记外部图片 URL；删除记录不会删除图片文件本身。
CREATE TABLE IF NOT EXISTS hotel_image (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    hotel_id BIGINT NOT NULL,
    url VARCHAR(500) NOT NULL,
    alt VARCHAR(200),
    sort_order INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_hotel_image_hotel (hotel_id, sort_order, id),
    CONSTRAINT fk_hotel_image_hotel FOREIGN KEY (hotel_id) REFERENCES hotel(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. route_itinerary_day 新增住宿安排列
SET @ddl := (
    SELECT IF(
        EXISTS(SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'route_itinerary_day'
                 AND COLUMN_NAME = 'accommodation_type'),
        'SELECT ''column already exists'' AS skipped',
        -- 默认 PENDING：存量行先统一按"待确认"，再由下面的回填把有酒店的改成 HOTEL。
        -- 不能默认 NONE —— "缺少酒店关联"不等于"不含住宿"。
        'ALTER TABLE route_itinerary_day ADD COLUMN accommodation_type VARCHAR(16) NOT NULL DEFAULT ''PENDING'' AFTER hotel_id'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := (
    SELECT IF(
        EXISTS(SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'route_itinerary_day'
                 AND COLUMN_NAME = 'accommodation_standard'),
        'SELECT ''column already exists'' AS skipped',
        'ALTER TABLE route_itinerary_day ADD COLUMN accommodation_standard VARCHAR(500) AFTER accommodation_type'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := (
    SELECT IF(
        EXISTS(SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'route_itinerary_day'
                 AND COLUMN_NAME = 'room_type'),
        'SELECT ''column already exists'' AS skipped',
        'ALTER TABLE route_itinerary_day ADD COLUMN room_type VARCHAR(100) AFTER accommodation_standard'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := (
    SELECT IF(
        EXISTS(SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'route_itinerary_day'
                 AND COLUMN_NAME = 'breakfast_included'),
        'SELECT ''column already exists'' AS skipped',
        -- 三态：1 含 / 0 不含 / NULL 尚未说明
        'ALTER TABLE route_itinerary_day ADD COLUMN breakfast_included TINYINT AFTER room_type'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := (
    SELECT IF(
        EXISTS(SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'route_itinerary_day'
                 AND COLUMN_NAME = 'accommodation_note'),
        'SELECT ''column already exists'' AS skipped',
        'ALTER TABLE route_itinerary_day ADD COLUMN accommodation_note VARCHAR(1000) AFTER breakfast_included'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := (
    SELECT IF(
        EXISTS(SELECT 1 FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'route_itinerary_day'
                 AND INDEX_NAME = 'idx_day_hotel'),
        'SELECT ''index already exists'' AS skipped',
        'ALTER TABLE route_itinerary_day ADD INDEX idx_day_hotel (hotel_id)'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4. 存量数据回填：已经关联酒店的当天行程按 HOTEL，未关联酒店的保持 PENDING。
--    只改这两类，不动任何 hotel_id / 行程内容；重复执行结果相同
--    （服务层保证 hotel_id 非空的行只能是 HOTEL，因此再次执行不会覆盖人为设置）。
UPDATE route_itinerary_day
SET accommodation_type = 'HOTEL'
WHERE hotel_id IS NOT NULL
  AND accommodation_type <> 'HOTEL';

-- 5. 住宿类型与酒店关联/住宿标准必须自洽（契约 ItineraryDayRequest 的住宿规则）。
--    必须放在回填之后：回填前"有酒店但类型是默认 PENDING"的行还没被纠正，直接加约束会失败。
--    服务层已经拦下不一致的请求，这一道挡的是绕过服务层的写入（导入脚本、手工 SQL）——
--    accommodation_type 有默认值，只写 hotel_id 而不写类型会静默产出自相矛盾的一行。
--    注意：MySQL 8.0.16 之前只解析 CHECK 而不执行，此时退化回"服务层保证"。
SET @ddl := (
    SELECT IF(
        EXISTS(SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
               WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'route_itinerary_day'
                 AND CONSTRAINT_NAME = 'ck_day_accommodation'),
        'SELECT ''constraint already exists'' AS skipped',
        'ALTER TABLE route_itinerary_day ADD CONSTRAINT ck_day_accommodation CHECK (
             (accommodation_type = ''HOTEL''    AND hotel_id IS NOT NULL)
          OR (accommodation_type = ''STANDARD'' AND hotel_id IS NULL
              AND accommodation_standard IS NOT NULL AND TRIM(accommodation_standard) <> '''')
          OR (accommodation_type = ''NONE''     AND hotel_id IS NULL)
          OR (accommodation_type = ''PENDING''  AND hotel_id IS NULL)
        )'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
