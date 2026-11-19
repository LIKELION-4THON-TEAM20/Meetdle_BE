ALTER TABLE room ADD COLUMN started_at DATETIME(6);
ALTER TABLE room_member ADD COLUMN hidden_at DATETIME(6);
ALTER TABLE app_user ADD COLUMN profile_image_url VARCHAR(2048);

-- Preserve existing nominations while allowing several places in one category.
CREATE INDEX ix_place_nomination_member ON place_nomination (room_id, round_no, participant_id);
ALTER TABLE place_nomination DROP PRIMARY KEY;
ALTER TABLE place_nomination ADD PRIMARY KEY (room_id, round_no, participant_id, candidate_id);

-- Historical rounds survive a room restart. Retry limits belong to a meeting selection.
CREATE INDEX ix_venue_round_selection ON venue_round (room_id, selection_id, round_no);
