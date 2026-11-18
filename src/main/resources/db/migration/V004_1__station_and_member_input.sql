-- A 소유 V004_1 (ERD 최종본 1~2장). 역 카탈로그, 참가자별 출발역·목적지, 가능 시간.

-- 자체 역 카탈로그 (A09 검색, A10 입력 검증, A11 계산에서 사용).
-- selectable = 서울 지하철 1~9호선 노선을 하나라도 지나는 역이면 TRUE.
CREATE TABLE station (
    station_id CHAR(36) PRIMARY KEY,
    provider_station_code VARCHAR(64),
    name VARCHAR(100) NOT NULL,
    lines_json VARCHAR(255) NOT NULL,
    lat DECIMAL(9, 6) NOT NULL,
    lng DECIMAL(9, 6) NOT NULL,
    selectable BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_station_provider_code UNIQUE (provider_station_code)
) ENGINE=InnoDB;

CREATE INDEX ix_station_name ON station (name);

-- 참가자 한 명당 입력 한 줄. 부분 저장이면 역이 비어 있을 수 있다.
-- completed_at이 채워지면 본인 입력은 더 이상 바꿀 수 없다 (명세 A10).
CREATE TABLE member_input (
    participant_id CHAR(36) PRIMARY KEY,
    origin_station_id CHAR(36),
    destination_station_id CHAR(36),
    input_revision BIGINT NOT NULL DEFAULT 0,
    completed_at DATETIME(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_member_input_member FOREIGN KEY (participant_id) REFERENCES room_member (participant_id),
    CONSTRAINT fk_member_input_origin FOREIGN KEY (origin_station_id) REFERENCES station (station_id),
    CONSTRAINT fk_member_input_destination FOREIGN KEY (destination_station_id) REFERENCES station (station_id),
    CONSTRAINT ck_member_input_revision CHECK (input_revision >= 0)
) ENGINE=InnoDB;

-- 가능 시간: (날짜, 시) 한 줄 = 1시간. 예: 2026-11-20 19시 → 19:00~20:00 가능
CREATE TABLE member_availability (
    participant_id CHAR(36) NOT NULL,
    available_date DATE NOT NULL,
    hour_of_day TINYINT NOT NULL,
    PRIMARY KEY (participant_id, available_date, hour_of_day),
    CONSTRAINT fk_member_availability_input FOREIGN KEY (participant_id) REFERENCES member_input (participant_id),
    CONSTRAINT ck_member_availability_hour CHECK (hour_of_day BETWEEN 10 AND 23)
) ENGINE=InnoDB;
