-- Use the database defaults to match the room and room_member tables from V002.
CREATE TABLE venue_round (
    room_id CHAR(36) NOT NULL,
    round_no SMALLINT NOT NULL,
    selection_id CHAR(36) NOT NULL,
    collection_closes_at DATETIME(6) NOT NULL,
    collection_closed_at DATETIME(6),
    collection_close_reason VARCHAR(32),
    mini_timer_used_at DATETIME(6),
    collection_extended_at DATETIME(6),
    vote_closes_at DATETIME(6),
    vote_review_started_at DATETIME(6),
    vote_extension_count TINYINT NOT NULL DEFAULT 0,
    vote_closed_at DATETIME(6),
    vote_close_reason VARCHAR(32),
    recovery_reason VARCHAR(32),
    PRIMARY KEY (room_id, round_no),
    CONSTRAINT fk_venue_round_room FOREIGN KEY (room_id) REFERENCES room (room_id),
    CONSTRAINT ck_venue_round_vote_extension CHECK (vote_extension_count BETWEEN 0 AND 1)
) ENGINE=InnoDB;

CREATE TABLE round_category (
    room_id CHAR(36) NOT NULL,
    round_no SMALLINT NOT NULL,
    category VARCHAR(16) NOT NULL,
    required BOOLEAN NOT NULL,
    available BOOLEAN NOT NULL,
    radius_meters SMALLINT NOT NULL,
    PRIMARY KEY (room_id, round_no, category),
    CONSTRAINT fk_round_category_round FOREIGN KEY (room_id, round_no) REFERENCES venue_round (room_id, round_no),
    CONSTRAINT ck_round_category_value CHECK (category IN ('FOOD', 'CAFE', 'CULTURE', 'BAR')),
    CONSTRAINT ck_round_category_culture CHECK (category <> 'CULTURE' OR required = FALSE),
    CONSTRAINT ck_round_category_radius CHECK (radius_meters IN (2500, 3000))
) ENGINE=InnoDB;

CREATE TABLE round_member (
    room_id CHAR(36) NOT NULL,
    round_no SMALLINT NOT NULL,
    participant_id CHAR(36) NOT NULL,
    registration_completed_at DATETIME(6),
    draft_revision BIGINT NOT NULL DEFAULT 0,
    submitted_at DATETIME(6),
    PRIMARY KEY (room_id, round_no, participant_id),
    CONSTRAINT fk_round_member_round FOREIGN KEY (room_id, round_no) REFERENCES venue_round (room_id, round_no),
    CONSTRAINT fk_round_member_member FOREIGN KEY (room_id, participant_id)
        REFERENCES room_member (room_id, participant_id)
) ENGINE=InnoDB;

CREATE TABLE place_candidate (
    room_id CHAR(36) NOT NULL,
    round_no SMALLINT NOT NULL,
    candidate_id CHAR(36) NOT NULL,
    category VARCHAR(16) NOT NULL,
    provider_place_id VARCHAR(128) NOT NULL,
    name VARCHAR(255) NOT NULL,
    address VARCHAR(255) NOT NULL,
    lat DECIMAL(10,7) NOT NULL,
    lng DECIMAL(10,7) NOT NULL,
    detail_url VARCHAR(2048),
    PRIMARY KEY (room_id, round_no, candidate_id),
    CONSTRAINT uq_place_candidate_provider UNIQUE (room_id, round_no, provider_place_id),
    CONSTRAINT uq_place_candidate_category UNIQUE (room_id, round_no, candidate_id, category),
    CONSTRAINT fk_place_candidate_category FOREIGN KEY (room_id, round_no, category)
        REFERENCES round_category (room_id, round_no, category)
) ENGINE=InnoDB;

CREATE TABLE place_nomination (
    room_id CHAR(36) NOT NULL,
    round_no SMALLINT NOT NULL,
    participant_id CHAR(36) NOT NULL,
    category VARCHAR(16) NOT NULL,
    candidate_id CHAR(36) NOT NULL,
    nominated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (room_id, round_no, participant_id, category),
    CONSTRAINT fk_place_nomination_member FOREIGN KEY (room_id, round_no, participant_id)
        REFERENCES round_member (room_id, round_no, participant_id),
    CONSTRAINT fk_place_nomination_candidate FOREIGN KEY (room_id, round_no, candidate_id, category)
        REFERENCES place_candidate (room_id, round_no, candidate_id, category)
) ENGINE=InnoDB;

CREATE TABLE ballot_candidate (
    room_id CHAR(36) NOT NULL,
    round_no SMALLINT NOT NULL,
    candidate_id CHAR(36) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    PRIMARY KEY (room_id, round_no, candidate_id),
    CONSTRAINT fk_ballot_candidate_place FOREIGN KEY (room_id, round_no, candidate_id)
        REFERENCES place_candidate (room_id, round_no, candidate_id),
    CONSTRAINT ck_ballot_candidate_mode CHECK (mode IN ('VOTE', 'AUTO_SELECTED'))
) ENGINE=InnoDB;

CREATE TABLE venue_result (
    room_id CHAR(36) NOT NULL,
    round_no SMALLINT NOT NULL,
    result_revision BIGINT NOT NULL,
    submitted_count SMALLINT NOT NULL,
    computed_at DATETIME(6) NOT NULL,
    finalized_at DATETIME(6),
    PRIMARY KEY (room_id, round_no),
    CONSTRAINT fk_venue_result_round FOREIGN KEY (room_id, round_no) REFERENCES venue_round (room_id, round_no)
) ENGINE=InnoDB;

CREATE TABLE result_place (
    room_id CHAR(36) NOT NULL,
    round_no SMALLINT NOT NULL,
    candidate_id CHAR(36) NOT NULL,
    result_status VARCHAR(32) NOT NULL,
    like_count SMALLINT,
    neutral_count SMALLINT,
    dislike_count SMALLINT,
    score INT,
    visit_position SMALLINT,
    PRIMARY KEY (room_id, round_no, candidate_id),
    CONSTRAINT uq_result_place_position UNIQUE (room_id, round_no, visit_position),
    CONSTRAINT fk_result_place_result FOREIGN KEY (room_id, round_no) REFERENCES venue_result (room_id, round_no),
    CONSTRAINT fk_result_place_candidate FOREIGN KEY (room_id, round_no, candidate_id)
        REFERENCES ballot_candidate (room_id, round_no, candidate_id)
) ENGINE=InnoDB;
