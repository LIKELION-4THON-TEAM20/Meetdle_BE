package com.likelion4thon.Meetdle.planning.subway;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * src/main/resources/subway/*.csv (공공데이터)를 읽어서
 * 1) 역 카탈로그(이름·노선·좌표)와 2) 노선도(역 사이 소요시간·환승시간)를 만든다.
 *
 * 🗺️ 노선도 만들기 규칙
 * - 1~8호선 CSV는 노선별로 역이 순서대로 있고, "소요시간"은 앞 역에서 이 역까지 걸리는 시간이다.
 * - 지선(갈라지는 선)은 시작 역을 직접 지정한다. 예: 2호선 "용답"은 바로 위 줄(시청)이 아니라 "성수"에서 갈라진다.
 * - 9호선은 거리만 있어서, 1~8호선 데이터로 구한 식(0.40분 + 1.05분/km)으로 시간을 추정한다.
 * - CSV 시간은 정차 시간이 빠진 "달리는 시간"이라서, 모든 노선에 역마다 30초 정차를 똑같이 더한다.
 *   (예: 9호선 개화~중앙보훈병원 추정 57.5분 + 정차 18.5분 ≈ 76분, 실제 일반열차와 비슷)
 */
@Component
public class SubwayData {

    /** 정차 시간: 역마다 30초 (추측값, 모든 노선 동일) */
    static final double DWELL_MINUTES = 0.5;
    /** 9호선 거리 → 시간 추정식 (1~8호선 271구간 회귀) */
    static final double LINE9_BASE_MINUTES = 0.40;
    static final double LINE9_MINUTES_PER_KM = 1.05;
    /** 환승 표에 없는 환승(예: 9호선 ↔ 다른 호선)의 걷는 시간 (추측값) */
    static final double DEFAULT_TRANSFER_WALK_MINUTES = 4.0;

    /** 같은 역인데 데이터마다 이름이 다른 경우 → 하나로 합친다 */
    private static final Map<String, String> KEY_ALIASES = Map.of(
            "이수", "총신대입구",
            "뚝섬유원지", "자양");
    /** 화면에 보여줄 이름을 정해 둔 역 */
    private static final Map<String, String> DISPLAY_NAMES = Map.of(
            "서울", "서울역",
            "총신대입구", "총신대입구(이수)",
            "자양", "자양(뚝섬한강공원)");
    /** 지선 시작 역: (호선, 지선 첫 역) → 갈라지는 역 */
    private static final Map<String, String> BRANCH_STARTS = Map.of(
            "2|용답", "성수",
            "2|도림천", "신도림",
            "5|둔촌동", "강동");

    private final Map<String, CatalogStation> stationsByKey = new LinkedHashMap<>();
    private final List<Segment> segments = new ArrayList<>();
    private final Map<String, Double> transferWalkMinutes = new HashMap<>();

    public SubwayData() {
        Map<String, String> displayFromSources = new HashMap<>();
        Map<String, double[]> coords = new HashMap<>();
        Map<String, TreeSet<String>> lines = new LinkedHashMap<>();

        // 1) 좌표
        for (Map<String, String> row : readCsv("subway/station_coords_1_8.csv")) {
            remember(row.get("역명"), coords, displayFromSources, row.get("위도"), row.get("경도"));
        }
        for (Map<String, String> row : readCsv("subway/station_coords_9.csv")) {
            remember(row.get("역명"), coords, displayFromSources, row.get("위도"), row.get("경도"));
        }
        for (Map<String, String> row : readCsv("subway/station_coords_extra.csv")) {
            remember(row.get("역명"), coords, displayFromSources, row.get("위도"), row.get("경도"));
        }

        // 2) 1~8호선 구간
        Map<String, String> previousByLine = new HashMap<>();
        for (Map<String, String> row : readCsv("subway/line1_8_time.csv")) {
            String line = row.get("호선").strip();
            String key = key(row.get("역명"));
            noteDisplay(row.get("역명"), key, displayFromSources);
            lines.computeIfAbsent(key, k -> new TreeSet<>()).add(line);
            String from = BRANCH_STARTS.getOrDefault(line + "|" + key, previousByLine.get(line));
            double minutes = parseMinutes(row.get("소요시간"));
            if (from != null && minutes > 0) {
                segments.add(new Segment(line, from, key, minutes + DWELL_MINUTES));
            }
            previousByLine.put(line, key);
        }

        // 3) 9호선 구간: "역간거리"는 다음 역까지의 거리
        List<Map<String, String>> line9 = readCsv("subway/line9_distance.csv");
        for (int i = 0; i < line9.size(); i++) {
            String key = key(line9.get(i).get("역명"));
            noteDisplay(line9.get(i).get("역명"), key, displayFromSources);
            lines.computeIfAbsent(key, k -> new TreeSet<>()).add("9");
            String distance = line9.get(i).get("역간거리");
            if (i + 1 < line9.size() && distance != null && !distance.isBlank()) {
                double minutes = LINE9_BASE_MINUTES + LINE9_MINUTES_PER_KM * Double.parseDouble(distance);
                segments.add(new Segment("9", key, key(line9.get(i + 1).get("역명")), minutes + DWELL_MINUTES));
            }
        }

        // 4) 환승 걷는 시간 (1~9호선끼리만)
        for (Map<String, String> row : readCsv("subway/transfer_time.csv")) {
            String toLine = row.get("환승노선").replace("호선", "").strip();
            if (!toLine.matches("[1-9]")) {
                continue;
            }
            String fromLine = row.get("호선").strip();
            String key = key(row.get("환승역명"));
            transferWalkMinutes.put(transferKey(key, fromLine, toLine), parseMinutes(row.get("환승소요시간")));
        }

        // 5) 역 카탈로그 완성
        lines.forEach((key, lineSet) -> {
            double[] latLng = coords.get(key);
            if (latLng == null) {
                throw new IllegalStateException("좌표가 없는 역이 있어요: " + key + " (station_coords_extra.csv에 추가 필요)");
            }
            String display = DISPLAY_NAMES.getOrDefault(key, displayFromSources.getOrDefault(key, key));
            stationsByKey.put(key, new CatalogStation(stationIdOf(key), key, display,
                    List.copyOf(lineSet), latLng[0], latLng[1]));
        });
    }

    /** 1~9호선 지원역 전체. 예: 강남 → lines [2], 고속터미널 → lines [3, 7, 9] */
    public List<CatalogStation> stations() {
        return List.copyOf(stationsByKey.values());
    }

    public List<Segment> segments() {
        return Collections.unmodifiableList(segments);
    }

    /** 환승 걷는 시간(분). 표에 없으면 기본 4분. 예: 서울역 1호선 → 4호선 = 2분 13초 */
    public double transferWalkMinutes(String stationKey, String fromLine, String toLine) {
        Double minutes = transferWalkMinutes.get(transferKey(stationKey, fromLine, toLine));
        if (minutes == null) {
            minutes = transferWalkMinutes.get(transferKey(stationKey, toLine, fromLine));
        }
        return minutes != null ? minutes : DEFAULT_TRANSFER_WALK_MINUTES;
    }

    /**
     * 데이터마다 다른 표기를 하나로: 괄호 지우기, 끝의 "역" 지우기, 별칭 합치기.
     * 예: "흑석(중앙대입구)" → 흑석, "서울역" → 서울, "신내역" → 신내, "이수" → 총신대입구
     */
    public static String key(String rawName) {
        String name = rawName.replaceAll("\\(.*?\\)", "").strip();
        if (name.endsWith("역") && name.length() > 2) {
            name = name.substring(0, name.length() - 1);
        }
        return KEY_ALIASES.getOrDefault(name, name);
    }

    /**
     * 역 이름으로 항상 같은 UUID를 만든다 (서버를 다시 켜도, 다른 컴퓨터여도 같은 ID).
     * 모양은 명세대로 UUID v4 형식(버전 4, variant 비트)을 맞춘다.
     */
    public static UUID stationIdOf(String key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(("meetdle-station:" + key).getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x40);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            long msb = 0;
            long lsb = 0;
            for (int i = 0; i < 8; i++) {
                msb = (msb << 8) | (hash[i] & 0xff);
                lsb = (lsb << 8) | (hash[i + 8] & 0xff);
            }
            return new UUID(msb, lsb);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void remember(String rawName, Map<String, double[]> coords, Map<String, String> display,
                                 String lat, String lng) {
        String key = key(rawName);
        noteDisplay(rawName, key, display);
        coords.putIfAbsent(key, new double[] {Double.parseDouble(lat.strip()), Double.parseDouble(lng.strip())});
    }

    /** 괄호가 있는 원래 이름이 있으면 그걸 보여준다. 예: 흑석 → "흑석(중앙대입구)" */
    private static void noteDisplay(String rawName, String key, Map<String, String> display) {
        String name = rawName.strip();
        if (name.contains("(") && !KEY_ALIASES.containsKey(name.replaceAll("\\(.*?\\)", "").strip())) {
            display.putIfAbsent(key, name);
        }
    }

    /** "02:13" → 2.2166분 */
    private static double parseMinutes(String mmss) {
        String[] parts = mmss.strip().split(":");
        return Integer.parseInt(parts[0]) + Integer.parseInt(parts[1]) / 60.0;
    }

    private static String transferKey(String stationKey, String fromLine, String toLine) {
        return stationKey + "|" + fromLine + "|" + toLine;
    }

    private static List<Map<String, String>> readCsv(String path) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return List.of();
            }
            String[] header = headerLine.replace("﻿", "").split(",", -1);
            List<Map<String, String>> rows = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] cells = line.split(",", -1);
                Map<String, String> row = new HashMap<>();
                for (int i = 0; i < header.length && i < cells.length; i++) {
                    row.put(header[i].strip(), cells[i]);
                }
                rows.add(row);
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException("지하철 데이터 파일을 읽을 수 없어요: " + path, e);
        }
    }

    /**
     * 카탈로그의 역 하나.
     * @param key  내부 비교용 이름 (예: 총신대입구)
     * @param name 화면 표시 이름 (예: 총신대입구(이수))
     */
    public record CatalogStation(UUID stationId, String key, String name, List<String> lines, double lat, double lng) {}

    /** 같은 노선의 이웃한 두 역 사이 (양방향으로 쓴다). minutes = 달리는 시간 + 정차 30초 */
    public record Segment(String line, String fromKey, String toKey, double minutes) {}
}
