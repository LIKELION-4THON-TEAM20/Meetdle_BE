package com.likelion4thon.Meetdle.planning.subway;

import static org.assertj.core.api.Assertions.assertThat;

import com.likelion4thon.Meetdle.planning.subway.SubwayRouter.Route;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 길찾기 결과가 실제와 비슷한지 확인한다.
 * 기준값은 2026-11-16 ODsay 대중교통 길찾기로 확인한 지하철 구간 시간(걷는 시간 제외):
 *   강남 → 홍대입구 약 38분(2호선), 서울역 → 강남 약 27분(4호선 → 2호선)
 */
class SubwayRouterTests {

    private final SubwayRouter router = new SubwayRouter(new SubwayData());

    @Test
    void gangnamToHongdaeIsOneLineAboutFortyMinutes() {
        Route route = route("강남", "홍대입구");
        assertThat(route.minutes()).isBetween(34.0, 42.0);
        assertThat(route.transfers()).isZero();
    }

    @Test
    void seoulStationToGangnamNeedsOneTransfer() {
        Route route = route("서울", "강남");
        assertThat(route.minutes()).isBetween(24.0, 33.0);
        assertThat(route.transfers()).isEqualTo(1);
    }

    @Test
    void sameStationIsZeroMinutes() {
        assertThat(route("강남", "강남")).isEqualTo(new Route(0, 0));
    }

    @Test
    void everyStationIsReachableFromEveryLine() {
        assertThat(router.routesFrom(SubwayData.stationIdOf("개화"))).hasSize(router.stationIds().size());
    }

    @Test
    void unknownStationHasNoRoutes() {
        assertThat(router.routesFrom(UUID.randomUUID())).isEmpty();
        assertThat(router.keyOf(UUID.randomUUID())).isEmpty();
    }

    private Route route(String fromKey, String toKey) {
        return router.routesFrom(SubwayData.stationIdOf(fromKey)).get(SubwayData.stationIdOf(toKey));
    }
}
