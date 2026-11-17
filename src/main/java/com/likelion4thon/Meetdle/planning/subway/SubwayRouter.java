package com.likelion4thon.Meetdle.planning.subway;

import com.likelion4thon.Meetdle.planning.subway.SubwayData.CatalogStation;
import com.likelion4thon.Meetdle.planning.subway.SubwayData.Segment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * 지하철 길찾기 🧭 (내비게이션이 쓰는 다익스트라 알고리즘)
 *
 * 노선도를 "(역, 호선)" 점들로 만든다. 예: 강남은 (강남,2) 점 하나, 고속터미널은 (고속터미널,3)·(7)·(9) 점 세 개.
 * - 같은 호선 이웃 역 사이: 달리는 시간 + 정차 30초
 * - 같은 역에서 호선 바꾸기(환승): 걷는 시간 + 기다리는 시간 3분 → 환승 1회로 센다
 * 한 역에서 출발해 모든 역까지 "가장 빨리 가는 시간"과 "그때의 환승 횟수"를 한 번에 구한다.
 *
 * 예: 강남 → 홍대입구 = 2호선만 타고 약 40분, 환승 0회
 */
@Component
public class SubwayRouter {

    /** 환승할 때 다음 열차를 기다리는 평균 시간 (추측값) */
    static final double TRANSFER_WAIT_MINUTES = 3.0;

    private final SubwayData data;
    private final Map<UUID, String> keyByStationId = new HashMap<>();
    private final Map<String, List<String>> linesByKey = new HashMap<>();
    /** 점 이름 "역|호선" → 이웃 점들 */
    private final Map<String, List<Edge>> graph = new HashMap<>();

    public SubwayRouter(SubwayData data) {
        this.data = data;
        for (CatalogStation station : data.stations()) {
            keyByStationId.put(station.stationId(), station.key());
            linesByKey.put(station.key(), station.lines());
        }
        for (Segment s : data.segments()) {
            addEdge(node(s.fromKey(), s.line()), node(s.toKey(), s.line()), s.minutes(), 0);
            addEdge(node(s.toKey(), s.line()), node(s.fromKey(), s.line()), s.minutes(), 0);
        }
        linesByKey.forEach((key, lines) -> {
            for (String from : lines) {
                for (String to : lines) {
                    if (!from.equals(to)) {
                        double cost = data.transferWalkMinutes(key, from, to) + TRANSFER_WAIT_MINUTES;
                        addEdge(node(key, from), node(key, to), cost, 1);
                    }
                }
            }
        });
    }

    /** 카탈로그에 있는 역이면 내부 이름(key). 테스트용 가짜 역 등 노선도에 없는 역이면 비어 있다. */
    public Optional<String> keyOf(UUID stationId) {
        return Optional.ofNullable(keyByStationId.get(stationId));
    }

    /** 노선도에 있는 모든 역의 ID */
    public List<UUID> stationIds() {
        return List.copyOf(keyByStationId.keySet());
    }

    /**
     * 한 역에서 모든 역까지의 최단 경로. 결과: 역 ID → (분, 환승 횟수).
     * 출발역이 노선도에 없으면 빈 Map.
     */
    public Map<UUID, Route> routesFrom(UUID originStationId) {
        String originKey = keyByStationId.get(originStationId);
        if (originKey == null) {
            return Map.of();
        }
        Map<String, Route> best = new HashMap<>();
        PriorityQueue<Visit> queue = new PriorityQueue<>();
        for (String line : linesByKey.get(originKey)) { // 출발역은 어느 호선으로든 바로 탈 수 있다
            Visit start = new Visit(node(originKey, line), 0, 0);
            best.put(start.node(), new Route(0, 0));
            queue.add(start);
        }
        while (!queue.isEmpty()) {
            Visit visit = queue.poll();
            Route known = best.get(visit.node());
            if (known != null && (visit.minutes() > known.minutes())) {
                continue;
            }
            for (Edge edge : graph.getOrDefault(visit.node(), List.of())) {
                double minutes = visit.minutes() + edge.minutes();
                int transfers = visit.transfers() + edge.transfers();
                Route current = best.get(edge.to());
                if (current == null || minutes < current.minutes()
                        || (minutes == current.minutes() && transfers < current.transfers())) {
                    best.put(edge.to(), new Route(minutes, transfers));
                    queue.add(new Visit(edge.to(), minutes, transfers));
                }
            }
        }
        // (역, 호선) 점 여러 개 중 가장 빠른 것을 그 역의 결과로
        Map<UUID, Route> byStation = new HashMap<>();
        keyByStationId.forEach((stationId, key) -> {
            Route fastest = null;
            for (String line : linesByKey.get(key)) {
                Route route = best.get(node(key, line));
                if (route != null && (fastest == null || route.minutes() < fastest.minutes())) {
                    fastest = route;
                }
            }
            if (fastest != null) {
                byStation.put(stationId, fastest);
            }
        });
        return byStation;
    }

    /** 공공데이터 버전 이름. 추천 결과에 "어떤 지도로 계산했는지" 기록한다. */
    public String matrixVersion() {
        return "subway-public-2026-11-16";
    }

    private void addEdge(String from, String to, double minutes, int transfers) {
        graph.computeIfAbsent(from, k -> new ArrayList<>()).add(new Edge(to, minutes, transfers));
    }

    private static String node(String key, String line) {
        return key + "|" + line;
    }

    /** 최단 경로 결과. 예: 강남 → 홍대입구 = Route(39.5분, 환승 0) */
    public record Route(double minutes, int transfers) {}

    private record Edge(String to, double minutes, int transfers) {}

    private record Visit(String node, double minutes, int transfers) implements Comparable<Visit> {
        @Override
        public int compareTo(Visit other) {
            int byTime = Double.compare(minutes, other.minutes);
            return byTime != 0 ? byTime : Integer.compare(transfers, other.transfers);
        }
    }
}
