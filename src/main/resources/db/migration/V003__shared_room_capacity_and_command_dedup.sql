ALTER TABLE room ADD COLUMN capacity SMALLINT NOT NULL DEFAULT 6;
ALTER TABLE room ADD CONSTRAINT ck_room_capacity CHECK (capacity BETWEEN 2 AND 6);
ALTER TABLE room ALTER COLUMN capacity DROP DEFAULT;

CREATE INDEX ix_room_member_user_room ON room_member (user_id, room_id);

CREATE TABLE command_dedup (
    user_id CHAR(36) NOT NULL,
    request_path VARCHAR(255) NOT NULL,
    idempotency_key CHAR(36) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    response_status SMALLINT,
    response_location VARCHAR(2048),
    response_json TEXT,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, request_path, idempotency_key),
    CONSTRAINT fk_command_dedup_user FOREIGN KEY (user_id) REFERENCES app_user (user_id),
    CONSTRAINT ck_command_dedup_success CHECK (response_status IS NULL OR response_status BETWEEN 200 AND 299)
) ENGINE=InnoDB;

CREATE INDEX ix_command_dedup_expiry ON command_dedup (expires_at);
