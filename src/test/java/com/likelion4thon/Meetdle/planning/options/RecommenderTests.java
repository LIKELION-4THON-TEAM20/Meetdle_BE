package com.likelion4thon.Meetdle.planning.options;

import static org.assertj.core.api.Assertions.assertThat;

import com.likelion4thon.Meetdle.planning.PlanningStateReader.Reason;
import com.likelion4thon.Meetdle.planning.options.RegionRecommender.Pick;
import com.likelion4thon.Meetdle.planning.options.RegionRecommender.Traveler;
import com.likelion4thon.Meetdle.planning.subway.SubwayRouter.Route;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 지역·일시 고르기 규칙을 가짜 노선(숫자 직접 지정)으로 확인한다. DB·스프링 없이 빠르게 돈다. */
class RecommenderTests {

    private static final UUID HOME_A = id(1);
    private static final UUID HOME_B = id(2);
    private static final UUID FAR = id(10);    // 둘 다 60분 → 차이 0이지만 멀다
    private static final UUID MID = id(11);    // 20분 / 30분
    private static final UUID EASY = id(12);   // 25분 / 32분, 환승 0
    private static final UUID NOWHERE = id(13); // B가 갈 길 없음

    private static final UUID P1 = id(101);
    private static final UUID P2 = id(102);

    /** 출발역 → (후보역 → 경로). 편도 시간 = 합산의 절반이 되게 왕복 같은 역으로 둔다. */
    private final Map<UUID, Map<UUID, Route>> routes = new HashMap<>();

    @Test
    void farStationWithZeroSpreadIsExcludedFirst() {
        // 왕복(출발=목적지)이라 합산 = 편도 × 2
        routes.put(HOME_A, Map.of(FAR, new Route(30, 1), MID, new Route(10, 1), EASY, new Route(12.5, 0),
                NOWHERE, new Route(5, 0)));
        routes.put(HOME_B, Map.of(FAR, new Route(30, 1), MID, new Route(15, 1), EASY, new Route(16, 0)));

        List<Pick> picks = recommend();

        // MID: 합산 20/30 → 평균 25, 차이 5 / EASY: 25/32 → 평균 28.5, 차이 3.5, 환승 0
        // FAR: 평균 60 > 25 + 15 → 제외. NOWHERE: B가 못 감 → 제외
        assertThat(picks).extracting(Pick::stationId).containsExactly(EASY, MID);
        assertThat(picks.get(0).reasons()).containsExactly(Reason.BALANCED, Reason.LOW_TRANSFER);
        assertThat(picks.get(1).reasons()).isEmpty();
        // 표시값은 출발 구간만: EASY 12.5분 / 16분
        assertThat(picks.get(0).meanMinutes()).isEqualTo(14.25);
        assertThat(picks.get(0).maxMinutes()).isEqualTo(16);
        assertThat(picks.get(0).timeSpreadMinutes()).isEqualTo(3.5);
        assertThat(picks.get(0).travel()).hasSize(2);
    }

    @Test
    void balancedAndLowTransferCanBeDifferentStations() {
        routes.put(HOME_A, Map.of(MID, new Route(10, 2), EASY, new Route(9, 0)));
        routes.put(HOME_B, Map.of(MID, new Route(10, 2), EASY, new Route(14, 0)));

        List<Pick> picks = recommend();

        // MID 차이 0 → BALANCED, EASY 환승 0 → LOW_TRANSFER
        assertThat(picks).extracting(Pick::stationId).containsExactly(MID, EASY);
        assertThat(picks.get(0).reasons()).containsExactly(Reason.BALANCED);
        assertThat(picks.get(1).reasons()).containsExactly(Reason.LOW_TRANSFER);
        assertThat(picks.get(1).meanTotalTransfers()).isZero();
    }

    @Test
    void noReachableStationGivesNoPick() {
        routes.put(HOME_A, Map.of(MID, new Route(10, 0)));
        routes.put(HOME_B, Map.of());

        assertThat(recommend()).isEmpty();
    }

    @Test
    void slotsKeepTopThreeInOrderAndNeedTwoPeople() {
        Map<UUID, Set<LocalDateTime>> availability = new LinkedHashMap<>();
        availability.put(P1, Set.of(at(1, 18), at(1, 19), at(2, 10), at(2, 11)));
        availability.put(P2, Set.of(at(1, 19), at(2, 10), at(2, 11), at(2, 12)));
        // 서울 1/1 18:00 = UTC 09:00. now를 그 직후로 두면 18시 칸은 지난 칸
        Instant now = Instant.parse("2030-01-01T09:00:00Z");

        List<SlotRecommender.Slot> slots = SlotRecommender.recommend(
                LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 2), availability, now);

        // 둘 다 되는 칸: 1/1 19, 1/2 10, 1/2 11 / 1/2 12는 한 명뿐이라 빠짐
        assertThat(slots).extracting(SlotRecommender.Slot::meetingAt).containsExactly(
                Instant.parse("2030-01-01T10:00:00Z"),
                Instant.parse("2030-01-02T01:00:00Z"),
                Instant.parse("2030-01-02T02:00:00Z"));
        assertThat(slots).allSatisfy(s -> assertThat(s.availableCount()).isEqualTo(2));
    }

    private List<Pick> recommend() {
        return RegionRecommender.recommend(
                List.of(new Traveler(P1, HOME_A, HOME_A), new Traveler(P2, HOME_B, HOME_B)),
                List.of(FAR, MID, EASY, NOWHERE),
                station -> routes.getOrDefault(station, Map.of()));
    }

    private static LocalDateTime at(int day, int hour) {
        return LocalDateTime.of(2030, 1, day, hour, 0);
    }

    private static UUID id(int n) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(n));
    }
}
