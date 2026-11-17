-- A11 추천 계산 결과 (방마다 딱 한 번 만들고 재시도하면 그대로 다시 돌려준다)
-- 시간은 초(INT)로 저장한다. 예: 37.5분 = 2250초

CREATE TABLE planning_options (
    options_id CHAR(36) PRIMARY KEY,
    room_id CHAR(36) NOT NULL,
    input_version BIGINT NOT NULL,
    matrix_version VARCHAR(64),
    calculation_started_at DATETIME(6) NOT NULL,
    calculated_at DATETIME(6),
    -- 계산에 고정된 입력 완료자 participant_id 목록. 예: ["2000...01","2000...02"]
    calculation_participants_json TEXT NOT NULL,
    CONSTRAINT fk_planning_options_room FOREIGN KEY (room_id) REFERENCES room (room_id),
    CONSTRAINT uq_planning_options_room UNIQUE (room_id),
    -- A12 meeting_selection이 (room_id, options_id)로 같은 방 옵션만 가리키게 할 때 쓴다
    CONSTRAINT uq_planning_options_room_options UNIQUE (room_id, options_id),
    CONSTRAINT ck_planning_options_input_version CHECK (input_version >= 0)
) ENGINE=InnoDB;

CREATE TABLE slot_option (
    options_id CHAR(36) NOT NULL,
    slot_id CHAR(36) NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NOT NULL,
    available_count SMALLINT NOT NULL,
    unavailable_participant_ids_json TEXT NOT NULL,
    -- 화면에 보여줄 순서 (0부터)
    display_order SMALLINT NOT NULL,
    PRIMARY KEY (options_id, slot_id),
    CONSTRAINT fk_slot_option_options FOREIGN KEY (options_id) REFERENCES planning_options (options_id),
    CONSTRAINT uq_slot_option_start UNIQUE (options_id, starts_at),
    CONSTRAINT ck_slot_option_available CHECK (available_count >= 2)
) ENGINE=InnoDB;

CREATE TABLE region_candidate (
    options_id CHAR(36) NOT NULL,
    candidate_id CHAR(36) NOT NULL,
    station_id CHAR(36) NOT NULL,
    -- 출발 구간(출발역 → 후보역) 표시값
    mean_seconds INT NOT NULL,
    max_seconds INT NOT NULL,
    mean_transfers DOUBLE NOT NULL,
    -- 순위 지표: 출발 구간 + 목적지 구간 합산
    total_time_spread_seconds INT NOT NULL,
    mean_total_transfers DOUBLE NOT NULL,
    reasons_json VARCHAR(64) NOT NULL,
    outbound_travel_json TEXT NOT NULL,
    display_order SMALLINT NOT NULL,
    PRIMARY KEY (options_id, candidate_id),
    CONSTRAINT fk_region_candidate_options FOREIGN KEY (options_id) REFERENCES planning_options (options_id),
    CONSTRAINT fk_region_candidate_station FOREIGN KEY (station_id) REFERENCES station (station_id),
    CONSTRAINT uq_region_candidate_station UNIQUE (options_id, station_id)
) ENGINE=InnoDB;
