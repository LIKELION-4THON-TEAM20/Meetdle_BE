CREATE TABLE app_user (
    user_id CHAR(36) PRIMARY KEY,
    provider VARCHAR(32) NOT NULL,
    provider_user_id VARCHAR(128) NOT NULL,
    nickname VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_app_user_provider_identity UNIQUE (provider, provider_user_id)
) ENGINE=InnoDB;
