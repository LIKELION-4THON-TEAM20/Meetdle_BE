CREATE TABLE room (
    room_id CHAR(36) PRIMARY KEY,
    title VARCHAR(50),
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL,
    phase VARCHAR(32) NOT NULL DEFAULT 'WAITING_FOR_DEPARTURE',
    revision BIGINT NOT NULL DEFAULT 0,
    input_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_room_date_order CHECK (starts_on <= ends_on),
    CONSTRAINT ck_room_revisions CHECK (revision >= 0 AND input_version >= 0)
) ENGINE=InnoDB;

CREATE TABLE room_member (
    participant_id CHAR(36) PRIMARY KEY,
    room_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    nickname VARCHAR(100) NOT NULL,
    role VARCHAR(16) NOT NULL,
    decision_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    joined_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_room_member_room FOREIGN KEY (room_id) REFERENCES room (room_id),
    CONSTRAINT fk_room_member_user FOREIGN KEY (user_id) REFERENCES app_user (user_id),
    CONSTRAINT uq_room_member_user UNIQUE (room_id, user_id),
    CONSTRAINT uq_room_member_participant UNIQUE (room_id, participant_id),
    CONSTRAINT ck_room_member_role CHECK (role IN ('HOST', 'MEMBER')),
    CONSTRAINT ck_room_member_decision_status CHECK (decision_status IN ('PENDING', 'DECIDER', 'OBSERVER'))
) ENGINE=InnoDB;
