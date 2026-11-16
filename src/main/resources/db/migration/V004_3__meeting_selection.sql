-- A12 일시·지역 확정. 재선택하면 행을 새로 추가하고 room.current_selection_id 포인터만 바꾼다.
-- 예: 신용산 19시 확정(선택1) → 사당 20시로 재선택(선택2). 선택1 행은 기록으로 남는다.

CREATE TABLE meeting_selection (
    selection_id CHAR(36) PRIMARY KEY,
    room_id CHAR(36) NOT NULL,
    options_id CHAR(36) NOT NULL,
    slot_id CHAR(36) NOT NULL,
    candidate_id CHAR(36) NOT NULL,
    selected_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_meeting_selection_room UNIQUE (room_id, selection_id),
    -- 같은 방의 옵션, 그 옵션 안의 일시·지역만 고를 수 있게 DB가 한 번 더 막는다
    CONSTRAINT fk_meeting_selection_options FOREIGN KEY (room_id, options_id)
        REFERENCES planning_options (room_id, options_id),
    CONSTRAINT fk_meeting_selection_slot FOREIGN KEY (options_id, slot_id)
        REFERENCES slot_option (options_id, slot_id),
    CONSTRAINT fk_meeting_selection_candidate FOREIGN KEY (options_id, candidate_id)
        REFERENCES region_candidate (options_id, candidate_id)
) ENGINE=InnoDB;

-- room.current_selection_id는 같은 방의 확정 선택만 가리킨다 (null이면 아직 확정 전)
ALTER TABLE room ADD CONSTRAINT fk_room_current_selection FOREIGN KEY (room_id, current_selection_id)
    REFERENCES meeting_selection (room_id, selection_id);
