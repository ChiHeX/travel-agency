-- travel-agency database schema
-- All records inserted by test-data.sql are software test/demo data only.
CREATE DATABASE IF NOT EXISTS travel_agency CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE travel_agency;

CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(32) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    nickname VARCHAR(32) NOT NULL,
    real_name VARCHAR(64),
    phone VARCHAR(20),
    email VARCHAR(128),
    avatar VARCHAR(500),
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1 active, 0 disabled',
    deleted TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sys_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    description VARCHAR(255),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sys_role_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_user_role (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sys_user_role (user_id, role_id),
    KEY idx_sys_user_role_user (user_id),
    CONSTRAINT fk_sys_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_sys_user_role_role FOREIGN KEY (role_id) REFERENCES sys_role(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS staff (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    employee_no VARCHAR(32) NOT NULL,
    department VARCHAR(64),
    position VARCHAR(64),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_staff_user (user_id),
    UNIQUE KEY uk_staff_employee_no (employee_no),
    CONSTRAINT fk_staff_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS guide (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT,
    name VARCHAR(64) NOT NULL,
    phone VARCHAR(20),
    intro VARCHAR(1000),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_guide_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS travel_route (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(200) NOT NULL,
    departure_city VARCHAR(64) NOT NULL,
    destination VARCHAR(255) NOT NULL,
    duration_days INT NOT NULL,
    description TEXT,
    cover_url VARCHAR(500),
    included TEXT,
    excluded TEXT,
    booking_notice TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    rating_avg DECIMAL(3,2) NOT NULL DEFAULT 0,
    rating_count INT NOT NULL DEFAULT 0,
    valid_booking_count INT NOT NULL DEFAULT 0,
    created_by BIGINT,
    deleted TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_route_status (status),
    KEY idx_route_destination (destination)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS attraction (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL,
    city VARCHAR(64) NOT NULL,
    address VARCHAR(255),
    longitude DECIMAL(10,7),
    latitude DECIMAL(10,7),
    intro TEXT,
    data_source VARCHAR(500),
    status TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_attraction_city (city)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS hotel (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL,
    address VARCHAR(255),
    contact_phone VARCHAR(20),
    longitude DECIMAL(10,7),
    latitude DECIMAL(10,7),
    intro TEXT,
    data_source VARCHAR(500),
    status TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS route_itinerary_day (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    route_id BIGINT NOT NULL,
    day_number INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT,
    transportation VARCHAR(255),
    meals VARCHAR(255),
    hotel_id BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_route_day (route_id, day_number),
    KEY idx_day_route (route_id),
    CONSTRAINT fk_day_route FOREIGN KEY (route_id) REFERENCES travel_route(id),
    CONSTRAINT fk_day_hotel FOREIGN KEY (hotel_id) REFERENCES hotel(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS route_itinerary_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    day_id BIGINT NOT NULL,
    sort_no INT NOT NULL DEFAULT 1,
    item_type VARCHAR(32) NOT NULL DEFAULT 'ATTRACTION',
    name VARCHAR(200) NOT NULL,
    description TEXT,
    attraction_id BIGINT,
    longitude DECIMAL(10,7),
    latitude DECIMAL(10,7),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_item_day (day_id),
    CONSTRAINT fk_item_day FOREIGN KEY (day_id) REFERENCES route_itinerary_day(id),
    CONSTRAINT fk_item_attraction FOREIGN KEY (attraction_id) REFERENCES attraction(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS departure (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    route_id BIGINT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    adult_price DECIMAL(12,2) NOT NULL,
    child_price DECIMAL(12,2) NOT NULL,
    max_people INT NOT NULL,
    reserved_people INT NOT NULL DEFAULT 0 COMMENT 'unpaid/paid pending orders holding capacity',
    confirmed_people INT NOT NULL DEFAULT 0,
    guide_id BIGINT,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_departure_route_date (route_id, start_date),
    KEY idx_departure_status (status),
    CONSTRAINT fk_departure_route FOREIGN KEY (route_id) REFERENCES travel_route(id),
    CONSTRAINT fk_departure_guide FOREIGN KEY (guide_id) REFERENCES guide(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_traveler (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    name VARCHAR(64) NOT NULL,
    gender VARCHAR(16) NOT NULL,
    birth_date DATE,
    id_type VARCHAR(32) NOT NULL,
    id_no VARCHAR(64) NOT NULL,
    phone VARCHAR(20),
    emergency_name VARCHAR(64) NOT NULL,
    emergency_phone VARCHAR(20),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_traveler_user (user_id),
    CONSTRAINT fk_traveler_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS travel_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_no VARCHAR(40) NOT NULL,
    user_id BIGINT NOT NULL,
    route_id BIGINT NOT NULL,
    departure_id BIGINT NOT NULL,
    contact_name VARCHAR(64) NOT NULL,
    contact_phone VARCHAR(20) NOT NULL,
    contact_email VARCHAR(128),
    adult_count INT NOT NULL DEFAULT 0,
    child_count INT NOT NULL DEFAULT 0,
    adult_unit_price DECIMAL(12,2) NOT NULL,
    child_unit_price DECIMAL(12,2) NOT NULL,
    total_amount DECIMAL(12,2) NOT NULL,
    status VARCHAR(32) NOT NULL,
    payment_status VARCHAR(20) NOT NULL DEFAULT 'UNPAID',
    paid_at DATETIME,
    confirmed_at DATETIME,
    cancelled_at DATETIME,
    completed_at DATETIME,
    remark VARCHAR(1000),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_order_user_status (user_id, status),
    KEY idx_order_departure (departure_id),
    CONSTRAINT fk_order_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_order_route FOREIGN KEY (route_id) REFERENCES travel_route(id),
    CONSTRAINT fk_order_departure FOREIGN KEY (departure_id) REFERENCES departure(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS order_traveler (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    traveler_id BIGINT,
    traveler_type VARCHAR(16) NOT NULL DEFAULT 'ADULT',
    name VARCHAR(64) NOT NULL,
    gender VARCHAR(16) NOT NULL,
    birth_date DATE,
    id_type VARCHAR(32) NOT NULL,
    id_no VARCHAR(64) NOT NULL,
    phone VARCHAR(20),
    emergency_name VARCHAR(64) NOT NULL,
    emergency_phone VARCHAR(20),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_order_traveler_order (order_id),
    CONSTRAINT fk_order_traveler_order FOREIGN KEY (order_id) REFERENCES travel_order(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS payment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    payment_no VARCHAR(64) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    third_party_trade_no VARCHAR(128),
    paid_at DATETIME,
    callback_payload TEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_payment_order (order_id),
    UNIQUE KEY uk_payment_no (payment_no),
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES travel_order(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS refund (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    original_order_status VARCHAR(32) NOT NULL,
    status VARCHAR(20) NOT NULL,
    reviewed_by BIGINT,
    reviewed_at DATETIME,
    review_comment VARCHAR(500),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_refund_status (status),
    CONSTRAINT fk_refund_order FOREIGN KEY (order_id) REFERENCES travel_order(id),
    CONSTRAINT fk_refund_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS favorite (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    route_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_favorite_user_route (user_id, route_id),
    CONSTRAINT fk_favorite_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_favorite_route FOREIGN KEY (route_id) REFERENCES travel_route(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS review (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    route_id BIGINT NOT NULL,
    rating TINYINT NOT NULL,
    content VARCHAR(1000) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'VISIBLE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_review_order (order_id),
    KEY idx_review_route (route_id),
    CONSTRAINT fk_review_order FOREIGN KEY (order_id) REFERENCES travel_order(id),
    CONSTRAINT fk_review_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_review_route FOREIGN KEY (route_id) REFERENCES travel_route(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    title VARCHAR(128) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    read_flag TINYINT NOT NULL DEFAULT 0,
    read_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_message_user_read (user_id, read_flag),
    CONSTRAINT fk_message_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS consultation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'WAIT_REPLY',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_consultation_user (user_id),
    CONSTRAINT fk_consultation_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS consultation_reply (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    consultation_id BIGINT NOT NULL,
    staff_id BIGINT NOT NULL,
    content VARCHAR(2000) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_reply_consultation (consultation_id),
    CONSTRAINT fk_reply_consultation FOREIGN KEY (consultation_id) REFERENCES consultation(id),
    CONSTRAINT fk_reply_staff FOREIGN KEY (staff_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS travel_guide_article (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(200) NOT NULL,
    summary VARCHAR(500),
    content LONGTEXT NOT NULL,
    city VARCHAR(64),
    destination VARCHAR(128),
    attraction_id BIGINT,
    cover_url VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    author_id BIGINT NOT NULL,
    published_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_article_status (status),
    CONSTRAINT fk_article_attraction FOREIGN KEY (attraction_id) REFERENCES attraction(id),
    CONSTRAINT fk_article_author FOREIGN KEY (author_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS operation_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operator_id BIGINT NOT NULL,
    module VARCHAR(64) NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    object_type VARCHAR(64),
    object_id VARCHAR(64),
    result VARCHAR(20) NOT NULL,
    detail VARCHAR(1000),
    ip_address VARCHAR(64),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_operation_log_created (created_at),
    CONSTRAINT fk_operation_log_operator FOREIGN KEY (operator_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS data_source (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    data_name VARCHAR(200) NOT NULL,
    source VARCHAR(500) NOT NULL,
    source_type VARCHAR(64),
    used_date DATE,
    license VARCHAR(500),
    remark VARCHAR(1000),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
