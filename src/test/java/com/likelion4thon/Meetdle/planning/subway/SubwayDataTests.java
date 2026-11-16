package com.likelion4thon.Meetdle.planning.subway;

import static org.assertj.core.api.Assertions.assertThat;

import com.likelion4thon.Meetdle.planning.subway.SubwayData.CatalogStation;
import com.likelion4thon.Meetdle.planning.subway.SubwayData.Segment;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 공공데이터 CSV로 만든 역 목록·노선도가 말이 되는지 확인한다. */
class SubwayDataTests {

    private final SubwayData data = new SubwayData();
    private final Map<String, CatalogStation> byName = data.stations().stream()
            .collect(Collectors.toMap(CatalogStation::name, Function.identity()));

    @Test
    void buildsOneStationPerPlaceWithAllItsLines() {
        // 1~8호선 276역 + 9호선 38역 + 암사역사공원 - 이름이 같은 환승역 합치기 → 270개
        assertThat(data.stations()).hasSize(270);
        assertThat(byName.get("강남").lines()).containsExactly("2");
        assertThat(byName.get("서울역").lines()).containsExactly("1", "4");
        assertThat(byName.get("고속터미널").lines()).containsExactly("3", "7", "9");
        assertThat(byName.get("총신대입구(이수)").lines()).containsExactly("4", "7"); // 이수(7) + 총신대입구(4)
        assertThat(byName.get("흑석(중앙대입구)").lines()).containsExactly("9");
        assertThat(byName).containsKeys("자양(뚝섬한강공원)", "암사역사공원", "신내", "까치산");
    }

    @Test
    void everyStationHasSeoulAreaCoordinates() {
        for (CatalogStation station : data.stations()) {
            assertThat(station.lat()).as(station.name()).isBetween(37.2, 37.8);
            assertThat(station.lng()).as(station.name()).isBetween(126.7, 127.3);
        }
    }

    @Test
    void branchesStartFromTheRightStation() {
        assertThat(data.segments()).contains(
                new Segment("2", "성수", "용답", 3.5),        // 성수지선: 3분 + 정차 30초
                new Segment("5", "강동", "둔촌동", 140 / 60.0)); // 마천지선: 1분 50초 + 30초
        assertThat(data.segments()).noneMatch(s -> s.fromKey().equals("시청") && s.toKey().equals("용답"));
        assertThat(data.segments()).noneMatch(s -> s.fromKey().equals("하남검단산") && s.toKey().equals("둔촌동"));
    }

    @Test
    void wholeNetworkIsConnected() {
        Map<String, Set<String>> neighbors = new HashMap<>();
        for (Segment s : data.segments()) {
            neighbors.computeIfAbsent(s.fromKey(), k -> new HashSet<>()).add(s.toKey());
            neighbors.computeIfAbsent(s.toKey(), k -> new HashSet<>()).add(s.fromKey());
        }
        Set<String> seen = new HashSet<>(List.of("시청"));
        ArrayDeque<String> queue = new ArrayDeque<>(seen);
        while (!queue.isEmpty()) {
            for (String next : neighbors.getOrDefault(queue.poll(), Set.of())) {
                if (seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        Set<String> all = data.stations().stream().map(CatalogStation::key).collect(Collectors.toSet());
        assertThat(seen).containsExactlyInAnyOrderElementsOf(all);
    }

    @Test
    void transferWalkTimesComeFromTheTableOrDefault() {
        assertThat(data.transferWalkMinutes("서울", "1", "4")).isCloseTo(2 + 13 / 60.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(data.transferWalkMinutes("서울", "4", "1")).isGreaterThan(0);
        assertThat(data.transferWalkMinutes("없는역", "1", "2")).isEqualTo(SubwayData.DEFAULT_TRANSFER_WALK_MINUTES);
    }

    @Test
    void stationIdsAreStableUuidV4Shaped() {
        assertThat(SubwayData.stationIdOf("강남")).isEqualTo(SubwayData.stationIdOf("강남"));
        assertThat(SubwayData.stationIdOf("강남")).isNotEqualTo(SubwayData.stationIdOf("역삼"));
        assertThat(SubwayData.stationIdOf("강남").version()).isEqualTo(4);
        assertThat(SubwayData.stationIdOf("강남").variant()).isEqualTo(2);
    }
}
