package com.likelion4thon.Meetdle.planning.options;

import com.likelion4thon.Meetdle.planning.PlanningStateReader.Reason;
import com.likelion4thon.Meetdle.planning.PlanningStateReader.Travel;
import com.likelion4thon.Meetdle.planning.subway.SubwayRouter.Route;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * A11 지역 후보 고르기 🗺️ (DB·스프링 없이 계산만 하는 부분이라 따로 떼어 둠)
 *
 * 1) 사람마다 합산: (출발역 → 후보역) + (후보역 → 목적지).
 *    후보역 → 목적지는 "목적지에서 후보역까지" 시간으로 대신한다 (양방향 같다고 가정).
 * 2) 너무 먼 역 빼기: 평균 합산 시간이 가장 짧은 역보다 15분 넘게 더 걸리면 제외.
 *    예: 최단 평균 50분 → 65분까지만 후보, 66분짜리 역은 탈락.
 * 3) 최대 2곳:
 *    BALANCED     = 가장 공평한 역 (최대 − 평균이 가장 작음, 같으면 평균이 짧은 쪽)
 *    LOW_TRANSFER = 환승이 가장 적은 역 (평균 환승 합이 가장 작음, 같으면 평균이 짧은 쪽)
 *    둘이 같은 역이면 그 역에 이유 2개를 달고, 두 번째로 공평한 역을 이유 없이 붙인다.
 * 4) 누군가 갈 길이 없는 역(노선도 밖)은 후보에서 뺀다.
 */
public final class RegionRecommender {

    /** 최단 평균보다 이만큼 넘게 길면 제외 (팀 결정값, 시연 후 조정 가능) */
    static final double POOL_MARGIN_MINUTES = 15.0;

    private RegionRecommender() {
    }

    /** 계산에 들어가는 한 사람. */
    public record Traveler(UUID participantId, UUID originStationId, UUID destinationStationId) {}

    /**
     * 뽑힌 후보 한 곳.
     * meanMinutes·maxMinutes·meanTransfers·travel = 출발 구간(출발역 → 후보역)만. 화면 표시용.
     * timeSpreadMinutes·meanTotalTransfers = 두 구간 합산. 순위용.
     */
    public record Pick(UUID stationId, List<Reason> reasons,
                       double meanMinutes, double maxMinutes, double meanTransfers,
                       double timeSpreadMinutes, double meanTotalTransfers, List<Travel> travel) {
        public Pick {
            reasons = List.copyOf(reasons);
            travel = List.copyOf(travel);
        }
    }

    /**
     * @param candidateStationIds 후보로 볼 역들 (지원역 전체)
     * @param routesFrom          역 ID → 그 역에서 모든 역까지 최단 경로
     */
    public static List<Pick> recommend(List<Traveler> travelers, Collection<UUID> candidateStationIds,
                                       Function<UUID, Map<UUID, Route>> routesFrom) {
        if (travelers.isEmpty()) {
            return List.of();
        }
        List<Stats> all = new ArrayList<>();
        for (UUID stationId : candidateStationIds) {
            Stats stats = measure(stationId, travelers, routesFrom);
            if (stats != null) {
                all.add(stats);
            }
        }
        if (all.isEmpty()) {
            return List.of();
        }

        double bestMean = all.stream().mapToDouble(Stats::meanTotal).min().orElseThrow();
        List<Stats> pool = all.stream()
                .filter(s -> s.meanTotal() <= bestMean + POOL_MARGIN_MINUTES + 1e-9)
                .toList();

        Comparator<Stats> byMeanThenId = Comparator.comparingDouble(Stats::meanTotal)
                .thenComparing(s -> s.stationId().toString());
        List<Stats> byBalance = pool.stream()
                .sorted(Comparator.comparingDouble(Stats::spread).thenComparing(byMeanThenId))
                .toList();
        Stats balanced = byBalance.get(0);
        Stats lowTransfer = pool.stream()
                .min(Comparator.comparingDouble(Stats::meanTotalTransfers).thenComparing(byMeanThenId))
                .orElseThrow();

        List<Pick> picks = new ArrayList<>();
        if (balanced.stationId().equals(lowTransfer.stationId())) {
            picks.add(balanced.toPick(List.of(Reason.BALANCED, Reason.LOW_TRANSFER)));
            if (byBalance.size() > 1) {
                picks.add(byBalance.get(1).toPick(List.of()));
            }
        } else {
            picks.add(balanced.toPick(List.of(Reason.BALANCED)));
            picks.add(lowTransfer.toPick(List.of(Reason.LOW_TRANSFER)));
        }
        return picks;
    }

    /** 한 후보역에 대해 모든 사람의 시간을 잰다. 한 명이라도 길이 없으면 null(후보 탈락). */
    private static Stats measure(UUID stationId, List<Traveler> travelers,
                                 Function<UUID, Map<UUID, Route>> routesFrom) {
        double sumTotal = 0;
        double maxTotal = 0;
        double sumTotalTransfers = 0;
        double sumOut = 0;
        double maxOut = 0;
        double sumOutTransfers = 0;
        List<Travel> travel = new ArrayList<>();
        for (Traveler t : travelers) {
            Route out = routesFrom.apply(t.originStationId()).get(stationId);
            Route back = routesFrom.apply(t.destinationStationId()).get(stationId);
            if (out == null || back == null) {
                return null;
            }
            double total = out.minutes() + back.minutes();
            sumTotal += total;
            maxTotal = Math.max(maxTotal, total);
            sumTotalTransfers += out.transfers() + back.transfers();
            sumOut += out.minutes();
            maxOut = Math.max(maxOut, out.minutes());
            sumOutTransfers += out.transfers();
            travel.add(new Travel(t.participantId(), out.minutes(), out.transfers()));
        }
        int n = travelers.size();
        return new Stats(stationId, sumTotal / n, maxTotal, sumTotalTransfers / n,
                sumOut / n, maxOut, sumOutTransfers / n, travel);
    }

    private record Stats(UUID stationId, double meanTotal, double maxTotal, double meanTotalTransfers,
                         double meanOut, double maxOut, double meanOutTransfers, List<Travel> travel) {
        double spread() {
            return maxTotal - meanTotal;
        }

        Pick toPick(List<Reason> reasons) {
            return new Pick(stationId, reasons, meanOut, maxOut, meanOutTransfers,
                    spread(), meanTotalTransfers, travel);
        }
    }
}
