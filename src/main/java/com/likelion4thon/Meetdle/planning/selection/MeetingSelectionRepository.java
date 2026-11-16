package com.likelion4thon.Meetdle.planning.selection;

import com.likelion4thon.Meetdle.contract.SelectedMeeting;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** meeting_selection 테이블 담당. 확정 선택 + 그 선택의 일시 칸·후보역을 묶어 Meeting으로 읽는다. */
@Repository
public class MeetingSelectionRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 선택 → 일시 칸 → 지역 후보 → 역을 한 번에 잇는 공통 SELECT */
    private static final String MEETING_SELECT = """
            SELECT ms.selection_id, ms.slot_id, ms.candidate_id, so.starts_at, so.ends_at,
                   s.station_id, s.name, s.lines_json, s.lat, s.lng
              FROM meeting_selection ms
              JOIN slot_option so ON so.options_id = ms.options_id AND so.slot_id = ms.slot_id
              JOIN region_candidate rc ON rc.options_id = ms.options_id AND rc.candidate_id = ms.candidate_id
              JOIN station s ON s.station_id = rc.station_id
            """;

    private final JdbcClient jdbc;

    public MeetingSelectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID selectionId, UUID roomId, UUID optionsId, UUID slotId, UUID candidateId, Instant now) {
        jdbc.sql("""
                        INSERT INTO meeting_selection (selection_id, room_id, options_id, slot_id, candidate_id, selected_at)
                        VALUES (:selectionId, :roomId, :optionsId, :slotId, :candidateId, :now)
                        """)
                .param("selectionId", selectionId.toString())
                .param("roomId", roomId.toString())
                .param("optionsId", optionsId.toString())
                .param("slotId", slotId.toString())
                .param("candidateId", candidateId.toString())
                .param("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    public Optional<StoredSelection> find(UUID selectionId) {
        return jdbc.sql(MEETING_SELECT + " WHERE ms.selection_id = :selectionId")
                .param("selectionId", selectionId.toString())
                .query((rs, rowNum) -> toStored(rs))
                .optional();
    }

    /** 이 방에 지금 확정된 선택 (room.current_selection_id). 확정 전이면 비어 있다. */
    public Optional<StoredSelection> findCurrent(UUID roomId) {
        return jdbc.sql(MEETING_SELECT + """
                         JOIN room r ON r.room_id = ms.room_id AND r.current_selection_id = ms.selection_id
                        WHERE r.room_id = :roomId
                        """)
                .param("roomId", roomId.toString())
                .query((rs, rowNum) -> toStored(rs))
                .optional();
    }

    private static StoredSelection toStored(ResultSet rs) throws SQLException {
        SelectedMeeting meeting = new SelectedMeeting(
                UUID.fromString(rs.getString("selection_id")),
                new SelectedMeeting.Station(
                        UUID.fromString(rs.getString("station_id")),
                        rs.getString("name"),
                        List.of(JSON.readValue(rs.getString("lines_json"), String[].class)),
                        rs.getBigDecimal("lat").doubleValue(),
                        rs.getBigDecimal("lng").doubleValue()),
                rs.getObject("starts_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                rs.getObject("ends_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
        return new StoredSelection(meeting,
                UUID.fromString(rs.getString("slot_id")),
                UUID.fromString(rs.getString("candidate_id")));
    }

    /** 응답용 Meeting + 어떤 일시 칸·후보를 골랐는지 (같은 선택을 다시 보냈는지 판별용) */
    public record StoredSelection(SelectedMeeting meeting, UUID slotId, UUID candidateId) {}
}
