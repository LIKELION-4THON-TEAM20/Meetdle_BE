package com.likelion4thon.Meetdle.planning.options;

import com.likelion4thon.Meetdle.contract.SelectedMeeting.Station;
import com.likelion4thon.Meetdle.planning.PlanningStateReader.PlanningOptions;
import com.likelion4thon.Meetdle.planning.PlanningStateReader.Reason;
import com.likelion4thon.Meetdle.planning.PlanningStateReader.RegionCandidate;
import com.likelion4thon.Meetdle.planning.PlanningStateReader.Score;
import com.likelion4thon.Meetdle.planning.PlanningStateReader.SlotOption;
import com.likelion4thon.Meetdle.planning.PlanningStateReader.Travel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * planning_options / slot_option / region_candidate 테이블 담당.
 * 시간은 초로 저장하고, 읽을 때 분(소수점 한 자리)으로 바꾼다. 예: 2250초 → 37.5분
 */
@Repository
public class PlanningOptionsRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;

    public PlanningOptionsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 계산 결과 한 벌을 저장한다. slots·picks 순서가 그대로 화면 순서가 된다. */
    public void insert(UUID optionsId, UUID roomId, long inputVersion, String matrixVersion, Instant now,
                       List<UUID> participantIds, List<SlotRecommender.Slot> slots,
                       List<RegionRecommender.Pick> picks) {
        jdbc.sql("""
                        INSERT INTO planning_options (options_id, room_id, input_version, matrix_version,
                            calculation_started_at, calculated_at, calculation_participants_json)
                        VALUES (:optionsId, :roomId, :inputVersion, :matrixVersion, :now, :now, :participants)
                        """)
                .param("optionsId", optionsId.toString())
                .param("roomId", roomId.toString())
                .param("inputVersion", inputVersion)
                .param("matrixVersion", matrixVersion)
                .param("now", utc(now))
                .param("participants", JSON.writeValueAsString(participantIds.stream().map(UUID::toString).toList()))
                .update();

        for (int i = 0; i < slots.size(); i++) {
            SlotRecommender.Slot slot = slots.get(i);
            jdbc.sql("""
                            INSERT INTO slot_option (options_id, slot_id, starts_at, ends_at, available_count,
                                unavailable_participant_ids_json, display_order)
                            VALUES (:optionsId, :slotId, :startsAt, :endsAt, :available, :unavailable, :order)
                            """)
                    .param("optionsId", optionsId.toString())
                    .param("slotId", UUID.randomUUID().toString())
                    .param("startsAt", utc(slot.meetingAt()))
                    .param("endsAt", utc(slot.meetingAt().plus(Duration.ofHours(1))))
                    .param("available", slot.availableCount())
                    .param("unavailable", JSON.writeValueAsString(
                            slot.unavailableParticipantIds().stream().map(UUID::toString).toList()))
                    .param("order", i)
                    .update();
        }

        for (int i = 0; i < picks.size(); i++) {
            RegionRecommender.Pick pick = picks.get(i);
            List<TravelJson> travel = pick.travel().stream()
                    .map(t -> new TravelJson(t.participantId().toString(), seconds(t.minutes()), t.transfers()))
                    .toList();
            jdbc.sql("""
                            INSERT INTO region_candidate (options_id, candidate_id, station_id, mean_seconds,
                                max_seconds, mean_transfers, total_time_spread_seconds, mean_total_transfers,
                                reasons_json, outbound_travel_json, display_order)
                            VALUES (:optionsId, :candidateId, :stationId, :mean, :max, :meanTransfers,
                                    :spread, :meanTotalTransfers, :reasons, :travel, :order)
                            """)
                    .param("optionsId", optionsId.toString())
                    .param("candidateId", UUID.randomUUID().toString())
                    .param("stationId", pick.stationId().toString())
                    .param("mean", seconds(pick.meanMinutes()))
                    .param("max", seconds(pick.maxMinutes()))
                    .param("meanTransfers", round2(pick.meanTransfers()))
                    .param("spread", seconds(pick.timeSpreadMinutes()))
                    .param("meanTotalTransfers", round2(pick.meanTotalTransfers()))
                    .param("reasons", JSON.writeValueAsString(pick.reasons().stream().map(Reason::name).toList()))
                    .param("travel", JSON.writeValueAsString(travel))
                    .param("order", i)
                    .update();
        }
    }

    /** 이 방의 계산 결과. 아직 계산 전이면 비어 있다. */
    public Optional<PlanningOptions> find(UUID roomId) {
        Optional<OptionsRow> row = jdbc.sql("""
                        SELECT options_id, input_version, matrix_version
                          FROM planning_options WHERE room_id = :roomId
                        """)
                .param("roomId", roomId.toString())
                .query((rs, rowNum) -> new OptionsRow(
                        UUID.fromString(rs.getString("options_id")),
                        rs.getLong("input_version"),
                        rs.getString("matrix_version")))
                .optional();
        return row.map(r -> new PlanningOptions(r.optionsId(), r.inputVersion(), r.matrixVersion(),
                findSlots(r.optionsId()), findRegions(r.optionsId())));
    }

    /** 계산에 고정된 참가자들 (A12·A08에서 쓸 예정). */
    public List<UUID> findParticipants(UUID optionsId) {
        String json = jdbc.sql("SELECT calculation_participants_json FROM planning_options WHERE options_id = :id")
                .param("id", optionsId.toString())
                .query(String.class)
                .single();
        return uuids(json);
    }

    private List<SlotOption> findSlots(UUID optionsId) {
        return jdbc.sql("""
                        SELECT slot_id, starts_at, available_count, unavailable_participant_ids_json
                          FROM slot_option WHERE options_id = :optionsId ORDER BY display_order
                        """)
                .param("optionsId", optionsId.toString())
                .query((rs, rowNum) -> new SlotOption(
                        UUID.fromString(rs.getString("slot_id")),
                        rs.getObject("starts_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        rs.getInt("available_count"),
                        uuids(rs.getString("unavailable_participant_ids_json"))))
                .list();
    }

    private List<RegionCandidate> findRegions(UUID optionsId) {
        return jdbc.sql("""
                        SELECT c.candidate_id, c.mean_seconds, c.max_seconds, c.mean_transfers,
                               c.total_time_spread_seconds, c.mean_total_transfers, c.reasons_json,
                               c.outbound_travel_json,
                               s.station_id, s.name, s.lines_json, s.lat, s.lng
                          FROM region_candidate c
                          JOIN station s ON s.station_id = c.station_id
                         WHERE c.options_id = :optionsId
                         ORDER BY c.display_order
                        """)
                .param("optionsId", optionsId.toString())
                .query((rs, rowNum) -> new RegionCandidate(
                        UUID.fromString(rs.getString("candidate_id")),
                        new Station(
                                UUID.fromString(rs.getString("station_id")),
                                rs.getString("name"),
                                List.of(JSON.readValue(rs.getString("lines_json"), String[].class)),
                                rs.getBigDecimal("lat").doubleValue(),
                                rs.getBigDecimal("lng").doubleValue()),
                        minutes(rs.getInt("mean_seconds")),
                        minutes(rs.getInt("max_seconds")),
                        rs.getDouble("mean_transfers"),
                        Arrays.stream(JSON.readValue(rs.getString("reasons_json"), String[].class))
                                .map(Reason::valueOf).toList(),
                        new Score(minutes(rs.getInt("total_time_spread_seconds")),
                                rs.getDouble("mean_total_transfers")),
                        Arrays.stream(JSON.readValue(rs.getString("outbound_travel_json"), TravelJson[].class))
                                .map(t -> new Travel(UUID.fromString(t.participantId()), minutes(t.seconds()),
                                        t.transfers()))
                                .toList()))
                .list();
    }

    private static List<UUID> uuids(String json) {
        return Arrays.stream(JSON.readValue(json, String[].class)).map(UUID::fromString).toList();
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static int seconds(double minutes) {
        return (int) Math.round(minutes * 60);
    }

    /** 초 → 분, 소수점 한 자리. 예: 2250 → 37.5, 1990 → 33.2 */
    private static double minutes(int seconds) {
        return Math.round(seconds / 6.0) / 10.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private record OptionsRow(UUID optionsId, long inputVersion, String matrixVersion) {}

    /** outbound_travel_json 한 칸. 예: {"participantId":"...","seconds":2250,"transfers":0} */
    record TravelJson(String participantId, int seconds, int transfers) {}
}
