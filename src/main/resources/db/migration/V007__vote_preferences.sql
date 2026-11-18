CREATE TABLE vote_preference (
    room_id CHAR(36) NOT NULL,
    round_no SMALLINT NOT NULL,
    participant_id CHAR(36) NOT NULL,
    candidate_id CHAR(36) NOT NULL,
    choice VARCHAR(16) NOT NULL,
    PRIMARY KEY (room_id, round_no, participant_id, candidate_id),
    CONSTRAINT fk_vote_preference_member FOREIGN KEY (room_id, round_no, participant_id)
        REFERENCES round_member (room_id, round_no, participant_id),
    CONSTRAINT fk_vote_preference_candidate FOREIGN KEY (room_id, round_no, candidate_id)
        REFERENCES ballot_candidate (room_id, round_no, candidate_id),
    CONSTRAINT ck_vote_preference_choice CHECK (choice IN ('LIKE', 'NEUTRAL', 'DISLIKE'))
) ENGINE=InnoDB;
