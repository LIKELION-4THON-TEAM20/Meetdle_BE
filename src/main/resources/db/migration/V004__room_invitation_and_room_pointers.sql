-- A 소유 V004 (ERD 최종본 4장). 방 초대와 방의 잠금 시각·확정 포인터.
-- 나머지 A 테이블(입력·역·경로·추천·선택)은 V004_1, V004_2 ... 로 이어서 추가한다.
-- (이미 적용된 파일을 고치면 Flyway 체크섬이 깨지므로, 수정 대신 새 파일을 만든다.)

ALTER TABLE room ADD COLUMN join_closed_at DATETIME(6);
ALTER TABLE room ADD COLUMN inputs_closed_at DATETIME(6);
-- meeting_selection 테이블을 만든 뒤 (room_id, current_selection_id) FK를 추가한다.
ALTER TABLE room ADD COLUMN current_selection_id CHAR(36);

-- 방당 초대 1개. 링크 토큰(43자)과 6자리 코드는 같은 초대에 묶이고 생성 7일 뒤 만료된다.
-- 원문 대신 HMAC 조회값(검색용)과 암호문(방장에게 다시 보여줄 때 복호화)을 저장한다.
CREATE TABLE room_invitation (
    invitation_id CHAR(36) PRIMARY KEY,
    room_id CHAR(36) NOT NULL,
    token_lookup_hash BINARY(32) NOT NULL,
    encrypted_token VARBINARY(255) NOT NULL,
    code_lookup_hash BINARY(32) NOT NULL,
    encrypted_code VARBINARY(255) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_room_invitation_room FOREIGN KEY (room_id) REFERENCES room (room_id),
    CONSTRAINT uq_room_invitation_room UNIQUE (room_id),
    CONSTRAINT uq_room_invitation_token UNIQUE (token_lookup_hash),
    CONSTRAINT uq_room_invitation_code UNIQUE (code_lookup_hash)
) ENGINE=InnoDB;
