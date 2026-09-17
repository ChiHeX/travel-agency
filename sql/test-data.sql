USE travel_agency;

-- The following rows are test/demo data only, not real travel agency operating data.
INSERT INTO sys_role (code, name, description) VALUES
    ('USER', '注册用户', '浏览、报名、订单与评价'),
    ('STAFF', '旅行社工作人员', '线路、团期、订单与退款运营'),
    ('GUIDE', '导游', '仅查看和执行本人负责的团期'),
    ('ADMIN', '系统管理员', '系统级用户与权限管理')
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description);

-- password_hash is the BCrypt hash for the demo password "password".
-- If the demo password ever needs to change, regenerate with BCrypt.hashpw(plain, BCrypt.gensalt(10))
-- and update both this file and any existing database rows.
INSERT INTO sys_user (username, password_hash, nickname, real_name, status, deleted)
VALUES ('admin', '$2a$10$P1/CvJy8Lm3Rs0A8m2fD2Om5NGZbDEkisGjmvQ8fpGyITLhCzurjy', '系统管理员', '演示管理员', 1, 0)
ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), nickname = VALUES(nickname), status = 1, deleted = 0;

INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id FROM sys_user u JOIN sys_role r ON r.code = 'ADMIN'
WHERE u.username = 'admin'
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

INSERT INTO attraction (name, city, address, longitude, latitude, intro, data_source, status)
VALUES
    ('大理古城', '大理', '云南省大理市大理古城', 100.1650000, 25.6940000, '示例景点资料，用于地图与行程演示。', '团队整理的测试数据；坐标仅用于软件演示', 1),
    ('丽江古城', '丽江', '云南省丽江市古城区', 100.2330000, 26.8720000, '示例景点资料，用于地图与行程演示。', '团队整理的测试数据；坐标仅用于软件演示', 1)
ON DUPLICATE KEY UPDATE intro = VALUES(intro);

INSERT INTO travel_route (name, departure_city, destination, duration_days, description, included, excluded, booking_notice, status)
VALUES ('彩云之南经典 6 日跟团游', '上海', '昆明·大理·丽江', 6,
        '示例线路，用于演示线路、行程、团期和订单业务闭环。',
        '交通、住宿、行程内景点首道门票', '个人消费、行程外活动、单房差', '请在出发前确认有效证件与紧急联系人信息。', 'PUBLISHED')
ON DUPLICATE KEY UPDATE description = VALUES(description), status = VALUES(status);

SET @route_id = (SELECT id FROM travel_route WHERE name = '彩云之南经典 6 日跟团游' ORDER BY id LIMIT 1);
SET @dali_id = (SELECT id FROM attraction WHERE name = '大理古城' ORDER BY id LIMIT 1);
SET @lijiang_id = (SELECT id FROM attraction WHERE name = '丽江古城' ORDER BY id LIMIT 1);

INSERT INTO hotel (name, address, contact_phone, intro, data_source, status)
SELECT '彩云之南演示酒店', '云南省大理市古城区', '000-00000000', '课程演示用酒店资料，不提供独立预订。', '团队原创测试资料', 1
WHERE NOT EXISTS (SELECT 1 FROM hotel WHERE name = '彩云之南演示酒店');
SET @hotel_id = (SELECT id FROM hotel WHERE name = '彩云之南演示酒店' ORDER BY id LIMIT 1);

INSERT INTO route_itinerary_day (route_id, day_number, title, description, transportation, meals, hotel_id)
VALUES
    (@route_id, 1, '上海 · 昆明', '抵达昆明，完成集合与入住。', '飞机 / 大巴', '晚餐自理', @hotel_id),
    (@route_id, 2, '昆明 · 大理', '前往大理古城，感受苍山洱海风光。', '旅游大巴', '早、午餐', @hotel_id),
    (@route_id, 3, '大理 · 丽江', '游览古城，前往丽江。', '旅游大巴', '早、午餐', @hotel_id)
ON DUPLICATE KEY UPDATE title = VALUES(title), description = VALUES(description), hotel_id = VALUES(hotel_id);

SET @day2_id = (SELECT id FROM route_itinerary_day WHERE route_id = @route_id AND day_number = 2 LIMIT 1);
SET @day3_id = (SELECT id FROM route_itinerary_day WHERE route_id = @route_id AND day_number = 3 LIMIT 1);
INSERT INTO route_itinerary_item (day_id, sort_no, item_type, name, description, attraction_id, longitude, latitude)
VALUES
    (@day2_id, 1, 'ATTRACTION', '大理古城', '古城步行游览。', @dali_id, 100.1650000, 25.6940000),
    (@day3_id, 1, 'ATTRACTION', '丽江古城', '古城步行游览。', @lijiang_id, 100.2330000, 26.8720000);

INSERT INTO departure (route_id, start_date, end_date, adult_price, child_price, max_people, status)
SELECT @route_id, DATE_ADD(CURRENT_DATE, INTERVAL 30 DAY), DATE_ADD(CURRENT_DATE, INTERVAL 35 DAY), 3980.00, 3280.00, 30, 'OPEN'
WHERE @route_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM departure WHERE route_id = @route_id AND start_date = DATE_ADD(CURRENT_DATE, INTERVAL 30 DAY));

INSERT INTO data_source (data_name, source, source_type, used_date, license, remark)
VALUES ('演示景点与线路基础资料', '团队原创整理的课程测试数据', 'TEAM_TEST_DATA', CURRENT_DATE, '仅限课程项目开发、测试与答辩演示', '不代表真实旅行社经营数据');

-- ---------------------------------------------------------------------------
-- 前后端联调用扩展数据。以下人员、订单、联系方式和评价均为虚构测试资料。
-- 脚本可以重复执行：新增资源均按业务名称或测试编号去重。
-- 演示账号的密码均为 password。
-- ---------------------------------------------------------------------------

INSERT INTO sys_user (username, password_hash, nickname, real_name, phone, email, status, deleted)
VALUES
    ('demo_user', '$2a$10$wWQn/oU4soMhQf4gYJbAceOg2ovPU7j0WUmdaINZ6geKt6VnRXAVe', '旅行测试员', '测试旅客', '13900000001', 'demo_user@example.test', 1, 0),
    ('demo_staff', '$2a$10$wWQn/oU4soMhQf4gYJbAceOg2ovPU7j0WUmdaINZ6geKt6VnRXAVe', '运营测试员', '测试运营', '13900000002', 'demo_staff@example.test', 1, 0),
    ('demo_guide_a', '$2a$10$wWQn/oU4soMhQf4gYJbAceOg2ovPU7j0WUmdaINZ6geKt6VnRXAVe', '小林导游', '林晓', '13900000003', 'demo_guide_a@example.test', 1, 0),
    ('demo_guide_b', '$2a$10$wWQn/oU4soMhQf4gYJbAceOg2ovPU7j0WUmdaINZ6geKt6VnRXAVe', '小周导游', '周远', '13900000004', 'demo_guide_b@example.test', 1, 0),
    ('guide_editor', '$2a$10$wWQn/oU4soMhQf4gYJbAceOg2ovPU7j0WUmdaINZ6geKt6VnRXAVe', '旅行编辑部', '课程攻略编辑', '13900000005', 'guide_editor@example.test', 1, 0)
ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), nickname = VALUES(nickname),
    real_name = VALUES(real_name), phone = VALUES(phone),
    email = VALUES(email), status = 1, deleted = 0;

INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id FROM sys_user u JOIN sys_role r ON r.code = 'USER'
WHERE u.username = 'demo_user'
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id FROM sys_user u JOIN sys_role r ON r.code = 'STAFF'
WHERE u.username IN ('demo_staff', 'guide_editor')
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id FROM sys_user u JOIN sys_role r ON r.code = 'GUIDE'
WHERE u.username IN ('demo_guide_a', 'demo_guide_b')
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

INSERT INTO staff (user_id, employee_no, department, position)
SELECT u.id, 'TEST-STAFF-001', '产品运营部', '课程测试运营'
FROM sys_user u WHERE u.username = 'demo_staff'
ON DUPLICATE KEY UPDATE department = VALUES(department), position = VALUES(position);

INSERT INTO guide (user_id, name, phone, intro, status)
SELECT u.id, '林晓', '13900000003', '课程演示用导游资料，负责华东与西南方向团期。', 'ACTIVE'
FROM sys_user u WHERE u.username = 'demo_guide_a'
ON DUPLICATE KEY UPDATE name = VALUES(name), phone = VALUES(phone), intro = VALUES(intro), status = VALUES(status);

INSERT INTO guide (user_id, name, phone, intro, status)
SELECT u.id, '周远', '13900000004', '课程演示用导游资料，负责华南与西北方向团期。', 'ACTIVE'
FROM sys_user u WHERE u.username = 'demo_guide_b'
ON DUPLICATE KEY UPDATE name = VALUES(name), phone = VALUES(phone), intro = VALUES(intro), status = VALUES(status);

INSERT INTO attraction (name, city, address, longitude, latitude, intro, data_source, status)
SELECT source_data.name, source_data.city, source_data.address, source_data.longitude, source_data.latitude,
       source_data.intro, '团队整理的课程测试数据；坐标仅用于软件演示', 1
FROM (
    SELECT '西湖' AS name, '杭州' AS city, '浙江省杭州市西湖区' AS address, 120.1300000 AS longitude, 30.2400000 AS latitude, '用于杭州线路和地图联调的虚构说明。' AS intro UNION ALL
    SELECT '灵隐寺', '杭州', '浙江省杭州市西湖区灵隐路', 120.1010000, 30.2390000, '用于杭州线路和地图联调的虚构说明。' UNION ALL
    SELECT '故宫博物院', '北京', '北京市东城区景山前街4号', 116.3970000, 39.9180000, '用于北京线路和地图联调的虚构说明。' UNION ALL
    SELECT '天坛公园', '北京', '北京市东城区天坛东路1号', 116.4070000, 39.8820000, '用于北京线路和地图联调的虚构说明。' UNION ALL
    SELECT '都江堰景区', '成都', '四川省成都市都江堰市公园路', 103.6170000, 30.9990000, '用于成都线路和地图联调的虚构说明。' UNION ALL
    SELECT '宽窄巷子', '成都', '四川省成都市青羊区金河路口', 104.0490000, 30.6710000, '用于成都线路和地图联调的虚构说明。' UNION ALL
    SELECT '张家界国家森林公园', '张家界', '湖南省张家界市武陵源区', 110.4830000, 29.3290000, '用于张家界线路和地图联调的虚构说明。' UNION ALL
    SELECT '天门山国家森林公园', '张家界', '湖南省张家界市永定区', 110.4810000, 29.0550000, '用于张家界线路和地图联调的虚构说明。' UNION ALL
    SELECT '鼓浪屿', '厦门', '福建省厦门市思明区鼓浪屿', 118.0700000, 24.4480000, '用于闽南线路和地图联调的虚构说明。' UNION ALL
    SELECT '南普陀寺', '厦门', '福建省厦门市思明区思明南路515号', 118.0980000, 24.4410000, '用于闽南线路和地图联调的虚构说明。' UNION ALL
    SELECT '喀纳斯景区', '阿勒泰', '新疆维吾尔自治区阿勒泰地区布尔津县', 87.0160000, 48.7040000, '用于新疆线路和地图联调的虚构说明。' UNION ALL
    SELECT '赛里木湖', '博尔塔拉', '新疆维吾尔自治区博尔塔拉蒙古自治州', 81.0750000, 44.6020000, '用于新疆线路和地图联调的虚构说明。' UNION ALL
    SELECT '广州塔', '广州', '广东省广州市海珠区阅江西路222号', 113.3240000, 23.1060000, '用于广州线路和地图联调的虚构说明。' UNION ALL
    SELECT '陈家祠', '广州', '广东省广州市荔湾区中山七路恩龙里34号', 113.2430000, 23.1250000, '用于广州线路和地图联调的虚构说明。' UNION ALL
    SELECT '平遥古城', '平遥', '山西省晋中市平遥县', 112.1750000, 37.1890000, '用于晋中线路和地图联调的虚构说明。' UNION ALL
    SELECT '云冈石窟', '大同', '山西省大同市云冈区云冈镇', 113.1220000, 40.1100000, '用于晋中线路和地图联调的虚构说明。'
) AS source_data
WHERE NOT EXISTS (
    SELECT 1 FROM attraction a WHERE a.name = source_data.name AND a.city = source_data.city
);

INSERT INTO hotel (name, address, contact_phone, longitude, latitude, intro, data_source, status)
SELECT source_data.name, source_data.address, source_data.phone, source_data.longitude, source_data.latitude,
       '课程演示用酒店资料，不提供独立预订。', '团队原创测试资料', 1
FROM (
    SELECT '杭州湖畔演示酒店' AS name, '浙江省杭州市西湖区' AS address, '000-00000001' AS phone, 120.1390000 AS longitude, 30.2290000 AS latitude UNION ALL
    SELECT '北京中轴线演示酒店', '北京市东城区', '000-00000002', 116.4070000, 39.9040000 UNION ALL
    SELECT '成都锦里演示酒店', '四川省成都市武侯区', '000-00000003', 104.0420000, 30.6500000 UNION ALL
    SELECT '张家界武陵源演示酒店', '湖南省张家界市武陵源区', '000-00000004', 110.5490000, 29.3470000 UNION ALL
    SELECT '厦门环岛路演示酒店', '福建省厦门市思明区', '000-00000005', 118.1120000, 24.4460000 UNION ALL
    SELECT '乌鲁木齐天山演示酒店', '新疆维吾尔自治区乌鲁木齐市', '000-00000006', 87.6170000, 43.8250000 UNION ALL
    SELECT '广州珠江演示酒店', '广东省广州市海珠区', '000-00000007', 113.3290000, 23.1080000 UNION ALL
    SELECT '平遥古城演示客栈', '山西省晋中市平遥县', '000-00000008', 112.1760000, 37.1900000
) AS source_data
WHERE NOT EXISTS (SELECT 1 FROM hotel h WHERE h.name = source_data.name);

INSERT INTO travel_route (name, departure_city, destination, duration_days, description, cover_url, included, excluded, booking_notice, status, rating_avg, rating_count, valid_booking_count, created_by)
SELECT source_data.name, source_data.departure_city, source_data.destination, source_data.duration_days,
       source_data.description, source_data.cover_url, source_data.included, source_data.excluded,
       source_data.booking_notice, 'PUBLISHED', source_data.rating_avg, source_data.rating_count,
       source_data.valid_booking_count, staff_user.id
FROM (
    SELECT '杭州西湖人文 3 日跟团游' AS name, '上海' AS departure_city, '杭州' AS destination, 3 AS duration_days,
           '课程测试线路：覆盖短线、低价团期与杭州关键词检索。' AS description,
           'https://images.unsplash.com/photo-1548919973-5cef591cdbc9?auto=format&fit=crop&w=1200&q=80' AS cover_url,
           '往返交通、2晚住宿、行程所列餐食及首道门票' AS included, '个人消费、单房差及自费项目' AS excluded,
           '本线路为课程测试资料，请于出发前确认参团人信息。' AS booking_notice, 4.60 AS rating_avg, 18 AS rating_count, 42 AS valid_booking_count UNION ALL
    SELECT '北京中轴线文化 4 日跟团游', '上海', '北京', 4, '课程测试线路：覆盖城市文化主题与中等价位筛选。',
           'https://images.unsplash.com/photo-1508804185872-d7badad00f7d?auto=format&fit=crop&w=1200&q=80',
           '往返交通、3晚住宿、行程所列餐食及首道门票', '个人消费、单房差及自费项目', '本线路为课程测试资料，请于出发前确认参团人信息。', 4.75, 36, 81 UNION ALL
    SELECT '成都熊猫与都江堰 4 日跟团游', '上海', '成都', 4, '课程测试线路：覆盖美食、城市与自然景点组合。',
           'https://images.unsplash.com/photo-1526495124232-a04e1849168c?auto=format&fit=crop&w=1200&q=80',
           '往返交通、3晚住宿、行程所列餐食及首道门票', '个人消费、单房差及自费项目', '本线路为课程测试资料，请于出发前确认参团人信息。', 4.82, 52, 106 UNION ALL
    SELECT '张家界奇峰秘境 4 日跟团游', '武汉', '张家界', 4, '课程测试线路：覆盖山地景观、不同出发城市与长周期团期。',
           'https://images.unsplash.com/photo-1528360983277-13d401cdc186?auto=format&fit=crop&w=1200&q=80',
           '往返交通、3晚住宿、行程所列餐食及首道门票', '个人消费、单房差及自费项目', '本线路为课程测试资料，请于出发前确认参团人信息。', 4.68, 27, 57 UNION ALL
    SELECT '厦门鼓浪屿慢游 3 日跟团游', '广州', '厦门', 3, '课程测试线路：覆盖海滨目的地、低价与满团状态展示。',
           'https://images.unsplash.com/photo-1548919973-5cef591cdbc9?auto=format&fit=crop&w=1200&q=80',
           '往返交通、2晚住宿、行程所列餐食及首道门票', '个人消费、单房差及自费项目', '本线路为课程测试资料，请于出发前确认参团人信息。', 4.50, 15, 39 UNION ALL
    SELECT '北疆喀纳斯全景 6 日跟团游', '北京', '乌鲁木齐·阿勒泰', 6, '课程测试线路：覆盖高价、长线与新疆关键词检索。',
           'https://images.unsplash.com/photo-1464817739973-0128fe77aaa1?auto=format&fit=crop&w=1200&q=80',
           '往返交通、5晚住宿、行程所列餐食及首道门票', '个人消费、单房差及自费项目', '本线路为课程测试资料，请于出发前确认参团人信息。', 4.90, 44, 73 UNION ALL
    SELECT '广州岭南风味 3 日跟团游', '深圳', '广州', 3, '课程测试线路：覆盖周边短线、城市地图和不同价格区间。',
           'https://images.unsplash.com/photo-1508804185872-d7badad00f7d?auto=format&fit=crop&w=1200&q=80',
           '往返交通、2晚住宿、行程所列餐食及首道门票', '个人消费、单房差及自费项目', '本线路为课程测试资料，请于出发前确认参团人信息。', 4.45, 12, 28 UNION ALL
    SELECT '晋中古城与石窟 5 日跟团游', '北京', '平遥·大同', 5, '课程测试线路：覆盖历史古建主题、关闭团期和分页测试。',
           'https://images.unsplash.com/photo-1526778548025-fa2f459cd5c1?auto=format&fit=crop&w=1200&q=80',
           '往返交通、4晚住宿、行程所列餐食及首道门票', '个人消费、单房差及自费项目', '本线路为课程测试资料，请于出发前确认参团人信息。', 4.58, 21, 49
) AS source_data
LEFT JOIN sys_user staff_user ON staff_user.username = 'demo_staff'
WHERE NOT EXISTS (SELECT 1 FROM travel_route r WHERE r.name = source_data.name AND r.deleted = 0);

INSERT INTO route_itinerary_day (route_id, day_number, title, description, transportation, meals, hotel_id)
SELECT r.id, source_data.day_number, source_data.title, source_data.description, source_data.transportation,
       source_data.meals, h.id
FROM (
    SELECT '杭州西湖人文 3 日跟团游' AS route_name, 1 AS day_number, '上海 · 杭州' AS title, '抵达杭州，入住后自由活动。' AS description, '高铁 / 旅游大巴' AS transportation, '晚餐自理' AS meals, '杭州湖畔演示酒店' AS hotel_name UNION ALL
    SELECT '杭州西湖人文 3 日跟团游', 2, '西湖 · 灵隐', '游览西湖与灵隐寺，体验杭州人文风景。', '旅游大巴', '早、午餐', '杭州湖畔演示酒店' UNION ALL
    SELECT '杭州西湖人文 3 日跟团游', 3, '杭州 · 上海', '早餐后返程，结束课程测试行程。', '高铁 / 旅游大巴', '早餐', '杭州湖畔演示酒店' UNION ALL
    SELECT '北京中轴线文化 4 日跟团游', 1, '上海 · 北京', '抵达北京，完成集合与入住。', '飞机 / 旅游大巴', '晚餐自理', '北京中轴线演示酒店' UNION ALL
    SELECT '北京中轴线文化 4 日跟团游', 2, '故宫博物院', '游览故宫博物院，了解中轴线文化。', '旅游大巴', '早、午餐', '北京中轴线演示酒店' UNION ALL
    SELECT '北京中轴线文化 4 日跟团游', 3, '天坛 · 城市漫步', '游览天坛公园，安排城市漫步时间。', '旅游大巴', '早、午餐', '北京中轴线演示酒店' UNION ALL
    SELECT '北京中轴线文化 4 日跟团游', 4, '北京 · 上海', '早餐后返程。', '飞机 / 旅游大巴', '早餐', '北京中轴线演示酒店' UNION ALL
    SELECT '成都熊猫与都江堰 4 日跟团游', 1, '上海 · 成都', '抵达成都，入住后自由活动。', '飞机 / 旅游大巴', '晚餐自理', '成都锦里演示酒店' UNION ALL
    SELECT '成都熊猫与都江堰 4 日跟团游', 2, '成都城市体验', '游览宽窄巷子，安排成都风味体验。', '旅游大巴', '早、午餐', '成都锦里演示酒店' UNION ALL
    SELECT '成都熊猫与都江堰 4 日跟团游', 3, '都江堰景区', '游览都江堰景区。', '旅游大巴', '早、午餐', '成都锦里演示酒店' UNION ALL
    SELECT '成都熊猫与都江堰 4 日跟团游', 4, '成都 · 上海', '早餐后返程。', '飞机 / 旅游大巴', '早餐', '成都锦里演示酒店' UNION ALL
    SELECT '张家界奇峰秘境 4 日跟团游', 1, '武汉 · 张家界', '抵达张家界，入住后自由活动。', '旅游大巴', '晚餐自理', '张家界武陵源演示酒店' UNION ALL
    SELECT '张家界奇峰秘境 4 日跟团游', 2, '国家森林公园', '游览张家界国家森林公园。', '旅游大巴', '早、午餐', '张家界武陵源演示酒店' UNION ALL
    SELECT '张家界奇峰秘境 4 日跟团游', 3, '天门山', '游览天门山国家森林公园。', '旅游大巴', '早、午餐', '张家界武陵源演示酒店' UNION ALL
    SELECT '张家界奇峰秘境 4 日跟团游', 4, '张家界 · 武汉', '早餐后返程。', '旅游大巴', '早餐', '张家界武陵源演示酒店' UNION ALL
    SELECT '厦门鼓浪屿慢游 3 日跟团游', 1, '广州 · 厦门', '抵达厦门，入住后自由活动。', '动车 / 旅游大巴', '晚餐自理', '厦门环岛路演示酒店' UNION ALL
    SELECT '厦门鼓浪屿慢游 3 日跟团游', 2, '鼓浪屿 · 南普陀', '游览鼓浪屿与南普陀寺。', '旅游大巴', '早、午餐', '厦门环岛路演示酒店' UNION ALL
    SELECT '厦门鼓浪屿慢游 3 日跟团游', 3, '厦门 · 广州', '早餐后返程。', '动车 / 旅游大巴', '早餐', '厦门环岛路演示酒店' UNION ALL
    SELECT '北疆喀纳斯全景 6 日跟团游', 1, '北京 · 乌鲁木齐', '抵达乌鲁木齐，完成集合与入住。', '飞机 / 旅游大巴', '晚餐自理', '乌鲁木齐天山演示酒店' UNION ALL
    SELECT '北疆喀纳斯全景 6 日跟团游', 2, '乌鲁木齐 · 阿勒泰', '前往阿勒泰方向，沿途安排休整。', '旅游大巴', '早、午餐', '乌鲁木齐天山演示酒店' UNION ALL
    SELECT '北疆喀纳斯全景 6 日跟团游', 3, '喀纳斯景区', '游览喀纳斯景区。', '旅游大巴', '早、午餐', '乌鲁木齐天山演示酒店' UNION ALL
    SELECT '北疆喀纳斯全景 6 日跟团游', 4, '赛里木湖', '游览赛里木湖及周边风景。', '旅游大巴', '早、午餐', '乌鲁木齐天山演示酒店' UNION ALL
    SELECT '北疆喀纳斯全景 6 日跟团游', 5, '阿勒泰 · 乌鲁木齐', '返回乌鲁木齐。', '旅游大巴', '早、午餐', '乌鲁木齐天山演示酒店' UNION ALL
    SELECT '北疆喀纳斯全景 6 日跟团游', 6, '乌鲁木齐 · 北京', '早餐后返程。', '飞机 / 旅游大巴', '早餐', '乌鲁木齐天山演示酒店' UNION ALL
    SELECT '广州岭南风味 3 日跟团游', 1, '深圳 · 广州', '抵达广州，入住后自由活动。', '城际列车 / 旅游大巴', '晚餐自理', '广州珠江演示酒店' UNION ALL
    SELECT '广州岭南风味 3 日跟团游', 2, '广州塔 · 陈家祠', '游览广州塔与陈家祠。', '旅游大巴', '早、午餐', '广州珠江演示酒店' UNION ALL
    SELECT '广州岭南风味 3 日跟团游', 3, '广州 · 深圳', '早餐后返程。', '城际列车 / 旅游大巴', '早餐', '广州珠江演示酒店' UNION ALL
    SELECT '晋中古城与石窟 5 日跟团游', 1, '北京 · 平遥', '抵达平遥，入住后自由活动。', '高铁 / 旅游大巴', '晚餐自理', '平遥古城演示客栈' UNION ALL
    SELECT '晋中古城与石窟 5 日跟团游', 2, '平遥古城', '游览平遥古城。', '旅游大巴', '早、午餐', '平遥古城演示客栈' UNION ALL
    SELECT '晋中古城与石窟 5 日跟团游', 3, '平遥 · 大同', '前往大同，沿途安排休整。', '旅游大巴', '早、午餐', '平遥古城演示客栈' UNION ALL
    SELECT '晋中古城与石窟 5 日跟团游', 4, '云冈石窟', '游览云冈石窟。', '旅游大巴', '早、午餐', '平遥古城演示客栈' UNION ALL
    SELECT '晋中古城与石窟 5 日跟团游', 5, '大同 · 北京', '早餐后返程。', '高铁 / 旅游大巴', '早餐', '平遥古城演示客栈'
) AS source_data
JOIN travel_route r ON r.name = source_data.route_name AND r.deleted = 0
LEFT JOIN hotel h ON h.name = source_data.hotel_name
WHERE NOT EXISTS (
    SELECT 1 FROM route_itinerary_day d WHERE d.route_id = r.id AND d.day_number = source_data.day_number
);

INSERT INTO route_itinerary_item (day_id, sort_no, item_type, name, description, attraction_id, longitude, latitude)
SELECT d.id, source_data.sort_no, source_data.item_type, source_data.name, source_data.description,
       a.id, a.longitude, a.latitude
FROM (
    SELECT '杭州西湖人文 3 日跟团游' AS route_name, 2 AS day_number, 1 AS sort_no, 'ATTRACTION' AS item_type, '西湖' AS name, '沿湖步行游览。' AS description, '杭州' AS city UNION ALL
    SELECT '杭州西湖人文 3 日跟团游', 2, 2, 'ATTRACTION', '灵隐寺', '参观灵隐寺。', '杭州' UNION ALL
    SELECT '北京中轴线文化 4 日跟团游', 2, 1, 'ATTRACTION', '故宫博物院', '按预约时段入园。', '北京' UNION ALL
    SELECT '北京中轴线文化 4 日跟团游', 3, 1, 'ATTRACTION', '天坛公园', '游览祈年殿周边。', '北京' UNION ALL
    SELECT '成都熊猫与都江堰 4 日跟团游', 2, 1, 'ATTRACTION', '宽窄巷子', '城市漫步。', '成都' UNION ALL
    SELECT '成都熊猫与都江堰 4 日跟团游', 3, 1, 'ATTRACTION', '都江堰景区', '游览水利工程遗址。', '成都' UNION ALL
    SELECT '张家界奇峰秘境 4 日跟团游', 2, 1, 'ATTRACTION', '张家界国家森林公园', '山地步道游览。', '张家界' UNION ALL
    SELECT '张家界奇峰秘境 4 日跟团游', 3, 1, 'ATTRACTION', '天门山国家森林公园', '按现场安排游览。', '张家界' UNION ALL
    SELECT '厦门鼓浪屿慢游 3 日跟团游', 2, 1, 'ATTRACTION', '鼓浪屿', '岛上步行游览。', '厦门' UNION ALL
    SELECT '厦门鼓浪屿慢游 3 日跟团游', 2, 2, 'ATTRACTION', '南普陀寺', '参观寺院区域。', '厦门' UNION ALL
    SELECT '北疆喀纳斯全景 6 日跟团游', 3, 1, 'ATTRACTION', '喀纳斯景区', '按景区交通安排游览。', '阿勒泰' UNION ALL
    SELECT '北疆喀纳斯全景 6 日跟团游', 4, 1, 'ATTRACTION', '赛里木湖', '沿湖观景。', '博尔塔拉' UNION ALL
    SELECT '广州岭南风味 3 日跟团游', 2, 1, 'ATTRACTION', '广州塔', '按预约时段登塔。', '广州' UNION ALL
    SELECT '广州岭南风味 3 日跟团游', 2, 2, 'ATTRACTION', '陈家祠', '参观岭南建筑。', '广州' UNION ALL
    SELECT '晋中古城与石窟 5 日跟团游', 2, 1, 'ATTRACTION', '平遥古城', '古城步行游览。', '平遥' UNION ALL
    SELECT '晋中古城与石窟 5 日跟团游', 4, 1, 'ATTRACTION', '云冈石窟', '按预约时段游览。', '大同'
) AS source_data
JOIN travel_route r ON r.name = source_data.route_name AND r.deleted = 0
JOIN route_itinerary_day d ON d.route_id = r.id AND d.day_number = source_data.day_number
LEFT JOIN attraction a ON a.name = source_data.name AND a.city = source_data.city
WHERE NOT EXISTS (
    SELECT 1 FROM route_itinerary_item i WHERE i.day_id = d.id AND i.sort_no = source_data.sort_no
);

INSERT INTO departure (route_id, start_date, end_date, adult_price, child_price, max_people, reserved_people, confirmed_people, guide_id, status)
SELECT r.id, DATE_ADD(CURRENT_DATE, INTERVAL source_data.start_offset DAY),
       DATE_ADD(CURRENT_DATE, INTERVAL source_data.start_offset + r.duration_days - 1 DAY),
       source_data.adult_price, source_data.child_price, source_data.max_people, source_data.reserved_people,
       source_data.confirmed_people, g.id, source_data.status
FROM (
    SELECT '杭州西湖人文 3 日跟团游' AS route_name, 7 AS start_offset, 1380.00 AS adult_price, 980.00 AS child_price, 30 AS max_people, 2 AS reserved_people, 12 AS confirmed_people, '林晓' AS guide_name, 'OPEN' AS status UNION ALL
    SELECT '杭州西湖人文 3 日跟团游', 35, 1480.00, 1080.00, 30, 0, 0, '林晓', 'OPEN' UNION ALL
    SELECT '北京中轴线文化 4 日跟团游', 14, 2980.00, 2280.00, 25, 1, 16, '林晓', 'OPEN' UNION ALL
    SELECT '北京中轴线文化 4 日跟团游', 48, 3180.00, 2480.00, 25, 0, 25, '林晓', 'FULL' UNION ALL
    SELECT '成都熊猫与都江堰 4 日跟团游', 10, 2680.00, 1980.00, 28, 3, 15, '林晓', 'OPEN' UNION ALL
    SELECT '成都熊猫与都江堰 4 日跟团游', 42, 2880.00, 2180.00, 28, 0, 0, '林晓', 'OPEN' UNION ALL
    SELECT '张家界奇峰秘境 4 日跟团游', 21, 2580.00, 1880.00, 26, 0, 10, '周远', 'OPEN' UNION ALL
    SELECT '张家界奇峰秘境 4 日跟团游', 56, 2780.00, 2080.00, 26, 0, 0, '周远', 'OPEN' UNION ALL
    SELECT '厦门鼓浪屿慢游 3 日跟团游', 5, 1680.00, 1180.00, 20, 0, 20, '周远', 'FULL' UNION ALL
    SELECT '厦门鼓浪屿慢游 3 日跟团游', 33, 1780.00, 1280.00, 20, 1, 8, '周远', 'OPEN' UNION ALL
    SELECT '厦门鼓浪屿慢游 3 日跟团游', -40, 1580.00, 1080.00, 20, 0, 20, '周远', 'FINISHED' UNION ALL
    SELECT '北疆喀纳斯全景 6 日跟团游', 18, 6980.00, 5980.00, 18, 1, 9, '周远', 'OPEN' UNION ALL
    SELECT '北疆喀纳斯全景 6 日跟团游', 60, 7280.00, 6280.00, 18, 0, 0, '周远', 'OPEN' UNION ALL
    SELECT '广州岭南风味 3 日跟团游', 12, 1280.00, 880.00, 30, 0, 13, '林晓', 'OPEN' UNION ALL
    SELECT '广州岭南风味 3 日跟团游', 40, 1380.00, 980.00, 30, 0, 0, '林晓', 'OPEN' UNION ALL
    SELECT '广州岭南风味 3 日跟团游', -55, 1180.00, 780.00, 30, 0, 30, '林晓', 'FINISHED' UNION ALL
    SELECT '晋中古城与石窟 5 日跟团游', 25, 3680.00, 2880.00, 24, 0, 0, '林晓', 'CLOSED' UNION ALL
    SELECT '晋中古城与石窟 5 日跟团游', 68, 3880.00, 3080.00, 24, 0, 0, '林晓', 'OPEN'
) AS source_data
JOIN travel_route r ON r.name = source_data.route_name AND r.deleted = 0
LEFT JOIN guide g ON g.name = source_data.guide_name
WHERE NOT EXISTS (
    SELECT 1 FROM departure d WHERE d.route_id = r.id
    AND d.start_date = DATE_ADD(CURRENT_DATE, INTERVAL source_data.start_offset DAY)
);

INSERT INTO user_traveler (user_id, name, gender, birth_date, id_type, id_no, phone, emergency_name, emergency_phone)
SELECT u.id, source_data.name, source_data.gender, source_data.birth_date, 'TEST_ID', source_data.id_no,
       source_data.phone, source_data.emergency_name, source_data.emergency_phone
FROM (
    SELECT '测试旅客甲' AS name, 'MALE' AS gender, '1995-06-12' AS birth_date, 'TEST-DEMO-001' AS id_no, '13900000001' AS phone, '测试紧急联系人甲' AS emergency_name, '13900000011' AS emergency_phone UNION ALL
    SELECT '测试旅客乙', 'FEMALE', '1998-09-20', 'TEST-DEMO-002', '13900000001', '测试紧急联系人乙', '13900000012' UNION ALL
    SELECT '测试儿童丙', 'FEMALE', '2016-04-08', 'TEST-DEMO-003', '13900000001', '测试紧急联系人甲', '13900000011'
) AS source_data
JOIN sys_user u ON u.username = 'demo_user'
WHERE NOT EXISTS (
    SELECT 1 FROM user_traveler t WHERE t.user_id = u.id AND t.id_no = source_data.id_no
);

INSERT INTO travel_order (order_no, user_id, route_id, departure_id, contact_name, contact_phone, contact_email,
                           adult_count, child_count, adult_unit_price, child_unit_price, total_amount,
                           status, payment_status, paid_at, confirmed_at, completed_at, remark)
SELECT source_data.order_no, u.id, r.id, d.id, '测试旅客甲', '13900000001', 'demo_user@example.test',
       source_data.adult_count, source_data.child_count, d.adult_price, d.child_price,
       d.adult_price * source_data.adult_count + d.child_price * source_data.child_count,
       source_data.status, source_data.payment_status, source_data.paid_at, source_data.confirmed_at,
       source_data.completed_at, '课程项目联调用虚构订单，请勿用于真实业务。'
FROM (
    SELECT 'TEST-ORDER-WAIT-PAY' AS order_no, '杭州西湖人文 3 日跟团游' AS route_name, 7 AS start_offset, 1 AS adult_count, 0 AS child_count,
           'WAIT_PAY' AS status, 'UNPAID' AS payment_status, NULL AS paid_at, NULL AS confirmed_at, NULL AS completed_at UNION ALL
    SELECT 'TEST-ORDER-WAIT-CONFIRM', '成都熊猫与都江堰 4 日跟团游', 10, 2, 0, 'PAID_WAIT_CONFIRM', 'PAID', DATE_SUB(NOW(), INTERVAL 1 DAY), NULL, NULL UNION ALL
    SELECT 'TEST-ORDER-CONFIRMED', '北京中轴线文化 4 日跟团游', 14, 1, 1, 'CONFIRMED', 'PAID', DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), NULL UNION ALL
    SELECT 'TEST-ORDER-REFUND-APPLYING', '北疆喀纳斯全景 6 日跟团游', 18, 1, 0, 'REFUND_APPLYING', 'PAID', DATE_SUB(NOW(), INTERVAL 4 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY), NULL UNION ALL
    SELECT 'TEST-ORDER-COMPLETED', '厦门鼓浪屿慢游 3 日跟团游', -40, 1, 0, 'COMPLETED', 'PAID', DATE_SUB(NOW(), INTERVAL 50 DAY), DATE_SUB(NOW(), INTERVAL 49 DAY), DATE_SUB(NOW(), INTERVAL 30 DAY) UNION ALL
    SELECT 'TEST-ORDER-REVIEWED', '广州岭南风味 3 日跟团游', -55, 1, 0, 'COMPLETED', 'PAID', DATE_SUB(NOW(), INTERVAL 65 DAY), DATE_SUB(NOW(), INTERVAL 64 DAY), DATE_SUB(NOW(), INTERVAL 45 DAY)
) AS source_data
JOIN sys_user u ON u.username = 'demo_user'
JOIN travel_route r ON r.name = source_data.route_name AND r.deleted = 0
JOIN departure d ON d.route_id = r.id AND d.start_date = DATE_ADD(CURRENT_DATE, INTERVAL source_data.start_offset DAY)
WHERE NOT EXISTS (SELECT 1 FROM travel_order o WHERE o.order_no = source_data.order_no);

INSERT INTO order_traveler (order_id, traveler_id, name, gender, birth_date, id_type, id_no, phone, emergency_name, emergency_phone)
SELECT o.id, t.id, t.name, t.gender, t.birth_date, t.id_type, t.id_no, t.phone, t.emergency_name, t.emergency_phone
FROM travel_order o
JOIN user_traveler t ON t.id_no = 'TEST-DEMO-001'
WHERE o.order_no IN ('TEST-ORDER-WAIT-PAY', 'TEST-ORDER-WAIT-CONFIRM', 'TEST-ORDER-CONFIRMED', 'TEST-ORDER-REFUND-APPLYING', 'TEST-ORDER-COMPLETED', 'TEST-ORDER-REVIEWED')
  AND NOT EXISTS (SELECT 1 FROM order_traveler ot WHERE ot.order_id = o.id AND ot.id_no = t.id_no);

INSERT INTO payment (order_id, payment_no, channel, amount, status, third_party_trade_no, paid_at, callback_payload)
SELECT o.id, CONCAT('TEST-PAY-', o.order_no), 'MOCK_ALIPAY', o.total_amount,
       CASE WHEN o.payment_status = 'PAID' THEN 'PAID' ELSE 'UNPAID' END,
       CASE WHEN o.payment_status = 'PAID' THEN CONCAT('TEST-TRADE-', o.order_no) ELSE NULL END,
       o.paid_at, CASE WHEN o.payment_status = 'PAID' THEN '{"testData":true}' ELSE NULL END
FROM travel_order o
WHERE o.order_no IN ('TEST-ORDER-WAIT-PAY', 'TEST-ORDER-WAIT-CONFIRM', 'TEST-ORDER-CONFIRMED', 'TEST-ORDER-REFUND-APPLYING', 'TEST-ORDER-COMPLETED', 'TEST-ORDER-REVIEWED')
  AND NOT EXISTS (SELECT 1 FROM payment p WHERE p.order_id = o.id);

INSERT INTO refund (order_id, user_id, amount, reason, original_order_status, status)
SELECT o.id, o.user_id, o.total_amount, '课程测试用退款申请。', 'CONFIRMED', 'APPLYING'
FROM travel_order o WHERE o.order_no = 'TEST-ORDER-REFUND-APPLYING'
  AND NOT EXISTS (SELECT 1 FROM refund rf WHERE rf.order_id = o.id);

INSERT INTO review (order_id, user_id, route_id, rating, content, status)
SELECT o.id, o.user_id, o.route_id, 5, '课程测试评价：用于验证线路详情页的评价展示与排序。', 'VISIBLE'
FROM travel_order o WHERE o.order_no = 'TEST-ORDER-REVIEWED'
  AND NOT EXISTS (SELECT 1 FROM review rv WHERE rv.order_id = o.id);

INSERT INTO favorite (user_id, route_id)
SELECT u.id, r.id FROM sys_user u CROSS JOIN travel_route r
WHERE u.username = 'demo_user'
  AND r.name IN ('杭州西湖人文 3 日跟团游', '北疆喀纳斯全景 6 日跟团游', '张家界奇峰秘境 4 日跟团游')
  AND NOT EXISTS (SELECT 1 FROM favorite f WHERE f.user_id = u.id AND f.route_id = r.id);

INSERT INTO sys_message (user_id, type, title, content, read_flag, read_at, created_at)
SELECT u.id, source_data.type, source_data.title, source_data.content, source_data.read_flag,
       CASE WHEN source_data.read_flag = 1 THEN DATE_SUB(NOW(), INTERVAL 2 DAY) ELSE NULL END,
       DATE_SUB(NOW(), INTERVAL source_data.age_days DAY)
FROM (
    SELECT 'ORDER_CONFIRMED' AS type, '报名已确认' AS title, '测试订单 TEST-ORDER-CONFIRMED 已确认，可在订单页查看。' AS content, 0 AS read_flag, 1 AS age_days UNION ALL
    SELECT 'REFUND_APPLYING', '退款申请已提交', '测试订单 TEST-ORDER-REFUND-APPLYING 正在等待运营审核。', 0, 2 UNION ALL
    SELECT 'SYSTEM', '测试资料说明', '此处消息均为课程项目联调用虚构数据。', 1, 5
) AS source_data
JOIN sys_user u ON u.username = 'demo_user'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_message m WHERE m.user_id = u.id AND m.title = source_data.title AND m.content = source_data.content
);

INSERT INTO consultation (user_id, title, content, status)
SELECT u.id, source_data.title, source_data.content, source_data.status
FROM (
    SELECT '是否可以携带儿童参团？' AS title, '课程测试咨询：用于验证待回复咨询状态。' AS content, 'WAIT_REPLY' AS status UNION ALL
    SELECT '单房差如何计算？', '课程测试咨询：用于验证已回复咨询状态。', 'REPLIED'
) AS source_data
JOIN sys_user u ON u.username = 'demo_user'
WHERE NOT EXISTS (SELECT 1 FROM consultation c WHERE c.user_id = u.id AND c.title = source_data.title);

INSERT INTO consultation_reply (consultation_id, staff_id, content)
SELECT c.id, staff_user.id, '课程测试回复：单房差和具体团期价格请以线路详情为准。'
FROM consultation c
JOIN sys_user user_account ON user_account.id = c.user_id AND user_account.username = 'demo_user'
JOIN sys_user staff_user ON staff_user.username = 'demo_staff'
WHERE c.title = '单房差如何计算？'
  AND NOT EXISTS (SELECT 1 FROM consultation_reply cr WHERE cr.consultation_id = c.id);

INSERT INTO travel_guide_article (title, summary, content, city, destination, attraction_id, cover_url, status, author_id, published_at)
SELECT source_data.title, source_data.summary, source_data.content, source_data.city, source_data.destination,
       a.id, source_data.cover_url, 'PUBLISHED', author.id, DATE_SUB(NOW(), INTERVAL source_data.age_days DAY)
FROM (
    SELECT '西湖慢游：一日步行路线' AS title, '课程测试攻略：用于验证杭州城市、最新与详情浏览。' AS summary,
           '这是一篇课程项目的测试攻略，不代表真实旅行建议。可用于验证攻略详情、发布者和地点跳转。' AS content,
           '杭州' AS city, '杭州' AS destination, '西湖' AS attraction_name,
           'https://images.unsplash.com/photo-1548919973-5cef591cdbc9?auto=format&fit=crop&w=1200&q=80' AS cover_url, 1 AS age_days UNION ALL
    SELECT '北京中轴线的建筑漫步', '课程测试攻略：用于验证北京相关内容。', '这是一篇课程项目的测试攻略，不代表真实旅行建议。', '北京', '北京', '故宫博物院',
           'https://images.unsplash.com/photo-1508804185872-d7badad00f7d?auto=format&fit=crop&w=1200&q=80', 2 UNION ALL
    SELECT '成都巷子里的慢节奏', '课程测试攻略：用于验证成都与发布者筛选。', '这是一篇课程项目的测试攻略，不代表真实旅行建议。', '成都', '成都', '宽窄巷子',
           'https://images.unsplash.com/photo-1526495124232-a04e1849168c?auto=format&fit=crop&w=1200&q=80', 3 UNION ALL
    SELECT '张家界观景前的准备清单', '课程测试攻略：用于验证张家界地点详情。', '这是一篇课程项目的测试攻略，不代表真实旅行建议。', '张家界', '张家界', '张家界国家森林公园',
           'https://images.unsplash.com/photo-1528360983277-13d401cdc186?auto=format&fit=crop&w=1200&q=80', 4 UNION ALL
    SELECT '鼓浪屿岛上半日散步', '课程测试攻略：用于验证厦门城市内容。', '这是一篇课程项目的测试攻略，不代表真实旅行建议。', '厦门', '厦门', '鼓浪屿',
           'https://images.unsplash.com/photo-1518509562904-e7ef99cdcc86?auto=format&fit=crop&w=1200&q=80', 5 UNION ALL
    SELECT '北疆风景的行前说明', '课程测试攻略：用于验证长文卡片及图片加载。', '这是一篇课程项目的测试攻略，不代表真实旅行建议。', '阿勒泰', '新疆', '喀纳斯景区',
           'https://images.unsplash.com/photo-1464817739973-0128fe77aaa1?auto=format&fit=crop&w=1200&q=80', 6 UNION ALL
    SELECT '岭南建筑里的下午茶', '课程测试攻略：用于验证广州地点内容。', '这是一篇课程项目的测试攻略，不代表真实旅行建议。', '广州', '广州', '陈家祠',
           'https://images.unsplash.com/photo-1508804185872-d7badad00f7d?auto=format&fit=crop&w=1200&q=80', 7 UNION ALL
    SELECT '古城与石窟的测试路线', '课程测试攻略：用于验证多个目的地与分页。', '这是一篇课程项目的测试攻略，不代表真实旅行建议。', '平遥', '山西', '平遥古城',
           'https://images.unsplash.com/photo-1526778548025-fa2f459cd5c1?auto=format&fit=crop&w=1200&q=80', 8
) AS source_data
JOIN sys_user author ON author.username = 'guide_editor'
LEFT JOIN attraction a ON a.name = source_data.attraction_name
WHERE NOT EXISTS (SELECT 1 FROM travel_guide_article article WHERE article.title = source_data.title);

INSERT INTO data_source (data_name, source, source_type, used_date, license, remark)
SELECT '扩展线路、订单与攻略测试资料', '团队原创整理的课程测试数据', 'TEAM_TEST_DATA', CURRENT_DATE,
       '仅限课程项目开发、测试与答辩演示', '包含虚构账号、订单、联系方式和评价，不代表真实旅行社经营数据'
WHERE NOT EXISTS (SELECT 1 FROM data_source WHERE data_name = '扩展线路、订单与攻略测试资料');
