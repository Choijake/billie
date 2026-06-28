CREATE TABLE IF NOT EXISTS search_reconciliation_checkpoint (
    checkpoint_key      VARCHAR(100) NOT NULL PRIMARY KEY,
    last_successful_end DATETIME(6)  NOT NULL,
    row_version         BIGINT       NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
