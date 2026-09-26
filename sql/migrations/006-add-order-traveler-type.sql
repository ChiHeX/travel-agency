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
UPDATE order_traveler AS traveler
JOIN (
    SELECT ranked.id, ranked.position, orders.adult_count
    FROM (
        SELECT id, order_id,
               ROW_NUMBER() OVER (PARTITION BY order_id ORDER BY id) AS position
        FROM order_traveler
    ) AS ranked
    JOIN travel_order AS orders ON orders.id = ranked.order_id
) AS snapshot ON snapshot.id = traveler.id
SET traveler.traveler_type = 'CHILD'
WHERE snapshot.position > snapshot.adult_count
  AND traveler.traveler_type = 'ADULT';
