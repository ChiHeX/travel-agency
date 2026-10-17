CREATE TABLE IF NOT EXISTS place_guide (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(200) NOT NULL,
    summary VARCHAR(500),
    city VARCHAR(64) NOT NULL,
    destination VARCHAR(128),
    cover_url VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    author_id BIGINT NOT NULL,
    published_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_place_guide_status_published (status, published_at),
    KEY idx_place_guide_city (city),
    KEY idx_place_guide_author (author_id),
    CONSTRAINT fk_place_guide_author FOREIGN KEY (author_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS place_guide_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    guide_id BIGINT NOT NULL,
    attraction_id BIGINT NOT NULL,
    sort_order INT NOT NULL,
    note VARCHAR(500),
    UNIQUE KEY uq_place_guide_attraction (guide_id, attraction_id),
    UNIQUE KEY uq_place_guide_order (guide_id, sort_order),
    CONSTRAINT fk_place_guide_item_guide FOREIGN KEY (guide_id) REFERENCES place_guide(id) ON DELETE CASCADE,
    CONSTRAINT fk_place_guide_item_attraction FOREIGN KEY (attraction_id) REFERENCES attraction(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
