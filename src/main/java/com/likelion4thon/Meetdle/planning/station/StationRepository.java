
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

    /**
     * A09 역 검색. 지원역(selectable)만, 이름에 검색어가 들어간 역을 찾는다.
     * 순서: 이름이 똑같은 역 → 검색어로 시작하는 역 → 중간에 들어간 역, 같으면 이름순.
     * 예: "강남" → 강남, 강남구청 / "이수" → 총신대입구(이수)
     *
     * @param query     실제로 찾을 글자 (끝의 "역"을 뗀 것. 예: 서울)
     * @param typedName 사용자가 친 그대로 (예: 서울역). 이 이름과 똑같은 역도 맨 앞으로 올린다
     */
    public List<Station> search(String query, String typedName, int limit) {

        // [기존] 백슬래시를 LIKE 이스케이프 문자로 사용
        // String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");

        // [수정] MySQL의 백슬래시 해석 문제를 방지하기 위해 '!' 사용
        String escaped = query.replace("!", "!!").replace("%", "!%").replace("_", "!_");

        return jdbc.sql("""
                        SELECT station_id, name, lines_json, lat, lng
                          FROM station

                         -- [기존] 백슬래시 ESCAPE 사용
                         -- WHERE selectable = TRUE AND name LIKE :contains ESCAPE '\\'

                         -- [수정] '!'를 ESCAPE 문자로 사용
                         WHERE selectable = TRUE AND name LIKE :contains ESCAPE '!'

                         ORDER BY CASE WHEN name = :exact OR name = :typed THEN 0

                                       -- [기존] 백슬래시 ESCAPE 사용
                                       -- WHEN name LIKE :prefix ESCAPE '\\' THEN 1

                                       -- [수정] '!'를 ESCAPE 문자로 사용
                                       WHEN name LIKE :prefix ESCAPE '!' THEN 1

                                       ELSE 2 END,
                                  name
                         LIMIT :limit
                        """)
                .param("contains", "%" + escaped + "%")
                .param("prefix", escaped + "%")
                .param("exact", query)
                .param("typed", typedName)
                .param("limit", limit)
                .query((rs, rowNum) -> new Station(
                        UUID.fromString(rs.getString("station_id")),
                        rs.getString("name"),
                        parseLines(rs.getString("lines_json")),
                        rs.getBigDecimal("lat").doubleValue(),
                        rs.getBigDecimal("lng").doubleValue()))
                .list();
    }

    /** 카탈로그 역을 저장한다. 이미 있으면 이름·노선·좌표를 최신 데이터로 바꾼다. */
    public void upsert(Station station, String providerStationCode) {
        int updated = jdbc.sql("""
                        UPDATE station SET provider_station_code = :code, name = :name, lines_json = :lines,
                                           lat = :lat, lng = :lng, selectable = TRUE
                         WHERE station_id = :id
                        """)
                .params(stationParams(station, providerStationCode))
                .update();
        if (updated == 0) {
            jdbc.sql("""
                            INSERT INTO station (station_id, provider_station_code, name, lines_json, lat, lng, selectable)
                            VALUES (:id, :code, :name, :lines, :lat, :lng, TRUE)
                            """)
                    .params(stationParams(station, providerStationCode))
                    .update();
        }
    }

    private static Map<String, Object> stationParams(Station station, String providerStationCode) {
        return Map.of(
                "id", station.stationId().toString(),
                "code", providerStationCode,
                "name", station.name(),
                "lines", JSON.writeValueAsString(station.lines()),
                "lat", station.lat(),
                "lng", station.lng());
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
