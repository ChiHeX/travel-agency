-- Existing databases created before hotel.version was added to schema.sql would
-- silently keep the old structure: HotelService#update now writes `version = version + 1`
-- (optimistic lock) and would fail with "Unknown column 'version' in 'field list'".
SET @ddl := (
    SELECT IF(
        EXISTS (
            SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'hotel'
              AND COLUMN_NAME = 'version'
        ),
        'SELECT 1',
        'ALTER TABLE hotel ADD COLUMN version INT NOT NULL DEFAULT 0 AFTER status'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
