SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Existing databases created before order_traveler.traveler_type was added to schema.sql
-- fail when creating an order because OrderService now inserts this snapshot field.
SET @ddl := (
    SELECT IF(
        EXISTS (
            SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'order_traveler'
              AND COLUMN_NAME = 'traveler_type'
        ),
        'SELECT 1',
        'ALTER TABLE order_traveler ADD COLUMN traveler_type VARCHAR(16) NOT NULL DEFAULT ''ADULT'' AFTER traveler_id'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Earlier order creation inserted adults before children. Restore that type for
-- existing complete snapshots; rerunning the migration leaves the result intact.
-- Count preceding IDs instead of using ROW_NUMBER(), which MySQL 5.7 lacks.
-- Materialize separately so the UPDATE does not read its own target table
-- through a mergeable derived table (MySQL error 1093).
DROP TEMPORARY TABLE IF EXISTS migration_006_traveler_position;
CREATE TEMPORARY TABLE migration_006_traveler_position (
    id BIGINT PRIMARY KEY,
    position BIGINT NOT NULL
);
INSERT INTO migration_006_traveler_position (id, position)
SELECT traveler.id, COUNT(preceding.id)
FROM order_traveler AS traveler
JOIN order_traveler AS preceding
  ON preceding.order_id = traveler.order_id AND preceding.id <= traveler.id
GROUP BY traveler.id;

UPDATE order_traveler AS traveler
JOIN migration_006_traveler_position AS snapshot ON snapshot.id = traveler.id
JOIN travel_order AS orders ON orders.id = traveler.order_id
SET traveler.traveler_type = 'CHILD'
WHERE snapshot.position > orders.adult_count
  AND traveler.traveler_type = 'ADULT';

DROP TEMPORARY TABLE migration_006_traveler_position;
