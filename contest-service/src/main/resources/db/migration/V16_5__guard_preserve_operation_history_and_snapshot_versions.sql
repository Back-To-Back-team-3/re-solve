CREATE TABLE contest_guard_operation_history (
    resource_type VARCHAR(30) NOT NULL,
    resource_id BIGINT NOT NULL,
    operation_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    request_hash CHAR(64) NULL,
    prepared_at DATETIME(6) NULL,
    state VARCHAR(30) NOT NULL,
    operation_result VARCHAR(30) NOT NULL,
    source_version BIGINT NOT NULL,
    resolution_path VARCHAR(30) NULL,
    reason VARCHAR(500) NULL,
    resolved_at DATETIME(6) NULL,
    PRIMARY KEY (resource_type, resource_id, operation_id)
);

INSERT INTO contest_guard_operation_history (
    resource_type, resource_id, operation_id, request_hash, prepared_at, state, operation_result, source_version
)
SELECT resource_type, resource_id, operation_id, request_hash, prepared_at, state, operation_result, source_version
FROM contest_resource_guards
WHERE operation_id IS NOT NULL AND operation_result IN ('COMMITTED', 'ABORTED');

ALTER TABLE contest_resource_guards
    ADD COLUMN snapshot_version BIGINT NULL,
    ADD COLUMN prepared_snapshot_version BIGINT NULL;

UPDATE contest_resource_guards
SET snapshot_version = source_version
WHERE resource_type = 'PROBLEM' AND source_status IS NOT NULL
  AND (
      source_status = 'ARCHIVED'
      OR operation_id IS NULL
      OR state = 'OPEN'
      OR (state = 'PREPARED' AND prepared_from_state = 'OPEN')
  );

ALTER TABLE contest_admin_operations
    MODIFY COLUMN operation_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL;
