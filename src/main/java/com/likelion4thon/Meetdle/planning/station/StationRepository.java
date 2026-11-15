package com.likelion4thon.Meetdle.planning.station;

import com.likelion4thon.Meetdle.contract.SelectedMeeting.Station;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 자체 역 카탈로그(station 테이블). A09 검색, A10 입력 검증, A11 계산에서 함께 쓴다.
 * lines_json 예: ["2","신분당"] → lines = [2, 신분당]
 */
@Repository
public class StationRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;

    public StationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 여러 역을 한 번에 읽는다. 없는 ID는 결과 Map에 빠진다. */
    public Map<UUID, CatalogStation> findByIds(Collection<UUID> stationIds) {
        if (stationIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = stationIds.stream().map(UUID::toString).toList();
        return jdbc.sql("""
                        SELECT station_id, name, lines_json, lat, lng, selectable
                          FROM station
                         WHERE station_id IN (:ids)
                        """)
                .param("ids", ids)
                .query((rs, rowNum) -> new CatalogStation(
                        new Station(
                                UUID.fromString(rs.getString("station_id")),
                                rs.getString("name"),
                                parseLines(rs.getString("lines_json")),
                                rs.getBigDecimal("lat").doubleValue(),
                                rs.getBigDecimal("lng").doubleValue()),
                        rs.getBoolean("selectable")))
                .list().stream()
                .collect(Collectors.toMap(s -> s.station().stationId(), Function.identity()));
    }

    private static List<String> parseLines(String linesJson) {
        return List.of(JSON.readValue(linesJson, String[].class));
    }

    /** @param selectable 1~9호선 지원역이면 true. false인 역은 입력에 쓸 수 없다. */
    public record CatalogStation(Station station, boolean selectable) {}
}
