package com.likelion4thon.Meetdle.room.repository;

import com.likelion4thon.Meetdle.room.domain.MemberInputRow;
import com.likelion4thon.Meetdle.room.dto.HourSlot;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** member_input(역·완료 여부)과 member_availability(가능 시간) 담당. */
@Repository
public class MemberInputRepository {

    private final JdbcClient jdbc;

    public MemberInputRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<MemberInputRow> find(UUID participantId) {
        return jdbc.sql("""
                        SELECT participant_id, origin_station_id, destination_station_id, input_revision, completed_at
                          FROM member_input WHERE participant_id = :participantId
                        """)
                .param("participantId", participantId.toString())
                .query((rs, rowNum) -> new MemberInputRow(
                        UUID.fromString(rs.getString("participant_id")),
                        toUuid(rs.getString("origin_station_id")),
                        toUuid(rs.getString("destination_station_id")),
                        rs.getLong("input_revision"),
                        toInstant(rs.getObject("completed_at", LocalDateTime.class))))
                .optional();
    }

    /** 날짜·시 순서로 정렬해서 돌려준다. 예: [11/20 19시, 11/20 20시, 11/21 18시] */
    public List<HourSlot> findSlots(UUID participantId) {
        return jdbc.sql("""
                        SELECT available_date, hour_of_day FROM member_availability
                         WHERE participant_id = :participantId
                         ORDER BY available_date, hour_of_day
                        """)
                .param("participantId", participantId.toString())
                .query((rs, rowNum) -> new HourSlot(rs.getObject("available_date", LocalDate.class),
                        rs.getInt("hour_of_day")))
                .list();
    }

    /** 처음 저장이면 INSERT, 이미 있으면 UPDATE. */
    public void save(MemberInputRow row) {
        int updated = jdbc.sql("""
                        UPDATE member_input
                           SET origin_station_id = :origin, destination_station_id = :destination,
                               input_revision = :revision, completed_at = :completedAt,
                               updated_at = CURRENT_TIMESTAMP(6)
                         WHERE participant_id = :participantId
                        """)
                .params(params(row))
                .update();
        if (updated == 0) {
            jdbc.sql("""
                            INSERT INTO member_input
                                (participant_id, origin_station_id, destination_station_id, input_revision, completed_at)
                            VALUES (:participantId, :origin, :destination, :revision, :completedAt)
                            """)
                    .params(params(row))
                    .update();
        }
    }

    /** 가능 시간 전체 교체: 지우고 새로 넣는다. */
    public void replaceSlots(UUID participantId, List<HourSlot> slots) {
        jdbc.sql("DELETE FROM member_availability WHERE participant_id = :participantId")
                .param("participantId", participantId.toString())
                .update();
        for (HourSlot slot : slots) {
            jdbc.sql("""
                            INSERT INTO member_availability (participant_id, available_date, hour_of_day)
                            VALUES (:participantId, :date, :hour)
                            """)
                    .param("participantId", participantId.toString())
                    .param("date", slot.date())
                    .param("hour", slot.hour())
                    .update();
        }
    }

    private static java.util.Map<String, Object> params(MemberInputRow row) {
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("participantId", row.participantId().toString());
        params.put("origin", row.originStationId() == null ? null : row.originStationId().toString());
        params.put("destination", row.destinationStationId() == null ? null : row.destinationStationId().toString());
        params.put("revision", row.inputRevision());
        params.put("completedAt", row.completedAt() == null ? null
                : LocalDateTime.ofInstant(row.completedAt(), ZoneOffset.UTC));
        return params;
    }

    private static UUID toUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static Instant toInstant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
    }
}
