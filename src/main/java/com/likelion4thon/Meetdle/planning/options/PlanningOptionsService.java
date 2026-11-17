package com.likelion4thon.Meetdle.planning.options;

import com.likelion4thon.Meetdle.common.error.ApiException;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.contract.RoomMember;
import com.likelion4thon.Meetdle.contract.RoomPhase;
import com.likelion4thon.Meetdle.planning.PlanningStateReader.PlanningOptions;
import com.likelion4thon.Meetdle.planning.station.StationRepository;
import com.likelion4thon.Meetdle.planning.station.StationRepository.CatalogStation;
import com.likelion4thon.Meetdle.planning.subway.SubwayRouter;
import com.likelion4thon.Meetdle.planning.subway.SubwayRouter.Route;
import com.likelion4thon.Meetdle.room.domain.LockedRoomRow;
import com.likelion4thon.Meetdle.room.domain.MemberInputRow;
import com.likelion4thon.Meetdle.room.repository.MemberInputRepository;
import com.likelion4thon.Meetdle.room.repository.RoomRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A11 일시·지역 후보 계산 🧮
 *
 * 🍕 피자 주문 비유: 방장이 "주문!"을 누르는 순간까지 메뉴를 고른 사람(입력 완료자)만 계산에 넣고,
 * 그 명단을 고정한다. 같은 버튼을 또 눌러도 새로 굽지 않고 이미 나온 피자(저장된 결과)를 그대로 준다.
 *
 * 순서
 * 1) 방 잠금 → 방장인지 확인
 * 2) 이미 계산한 결과가 있으면 그대로 200
 * 3) 단계·입력 버전·완료자 2명 이상 확인
 * 4) 일시 후보(최대 3) + 지역 후보(최대 2) 계산 → 하나라도 없으면 409 NO_FEASIBLE_OPTION (아무것도 안 바뀜)
 * 5) 저장 + 가입 마감 + 단계 OPTIONS_READY → 201
 */
@Service
public class PlanningOptionsService {

    static final int MIN_COMPLETED_INPUTS = 2;

    private final RoomRepository roomRepository;
    private final MemberInputRepository inputRepository;
    private final StationRepository stationRepository;
    private final PlanningOptionsRepository optionsRepository;
    private final SubwayRouter router;
    private final Clock clock;

    public PlanningOptionsService(RoomRepository roomRepository, MemberInputRepository inputRepository,
                                  StationRepository stationRepository, PlanningOptionsRepository optionsRepository,
                                  SubwayRouter router, Clock clock) {
        this.roomRepository = roomRepository;
        this.inputRepository = inputRepository;
        this.stationRepository = stationRepository;
        this.optionsRepository = optionsRepository;
        this.router = router;
        this.clock = clock;
    }

    /** @param created true면 이번에 새로 계산(201), false면 저장된 결과 재사용(200) */
    public record Result(PlanningOptions options, boolean created) {}

    @Transactional
    public Result calculate(AuthenticatedUser user, UUID roomId, long inputVersion) {
        // 1) 방 잠금 → 방장 확인
        LockedRoomRow room = roomRepository.lockRoom(roomId)
                .orElseThrow(() -> ApiException.of(HttpStatus.NOT_FOUND, "NOT_FOUND", "방을 찾을 수 없어요."));
        RoomMember me = roomRepository.findMember(roomId, user.userId())
                .orElseThrow(() -> ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "이 방의 회원이 아니에요."));
        if (me.role() != RoomMember.Role.HOST) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "방장만 추천을 계산할 수 있어요.");
        }

        // 2) 재시도: 저장된 결과 그대로
        Optional<PlanningOptions> existing = optionsRepository.find(roomId);
        if (existing.isPresent()) {
            return new Result(existing.get(), false);
        }

        // 3) 계산해도 되는 상태인지
        if (room.phase() != RoomPhase.WAITING_FOR_DEPARTURE) {
            throw ApiException.of(HttpStatus.CONFLICT, "WRONG_PHASE", "지금은 추천을 계산할 수 없는 단계예요.");
        }
        if (inputVersion != room.inputVersion()) {
            // 예: 방장 화면은 버전 5를 보고 있는데 그사이 누가 입력을 바꿔서 6이 됨
            throw new ApiException(HttpStatus.CONFLICT, "STALE_INPUT",
                    "그사이 입력이 바뀌었어요. 새로고침 후 다시 시도해 주세요.",
                    Map.of("currentInputVersion", room.inputVersion()));
        }
        List<MemberInputRow> completed = inputRepository.findCompletedInRoom(roomId);
        if (completed.size() < MIN_COMPLETED_INPUTS) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_ENOUGH_INPUTS",
                    "입력을 완료한 사람이 2명 이상이어야 해요.",
                    Map.of("completedCount", completed.size()));
        }
        if (router.stationIds().isEmpty()) {
            throw ApiException.of(HttpStatus.SERVICE_UNAVAILABLE, "MATRIX_UNAVAILABLE",
                    "이동시간 데이터를 불러오지 못했어요.");
        }

        // 4) 계산
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<SlotRecommender.Slot> slots = SlotRecommender.recommend(
                room.startsOn(), room.endsOn(), availabilityOf(completed), now);
        List<RegionRecommender.Pick> picks = RegionRecommender.recommend(
                completed.stream()
                        .map(i -> new RegionRecommender.Traveler(
                                i.participantId(), i.originStationId(), i.destinationStationId()))
                        .toList(),
                selectableStationIds(),
                cachedRoutes());
        if (slots.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_FEASIBLE_OPTION",
                    "2명 이상 함께 되는 시간이 없어요.", Map.of("reason", "NO_SLOT"));
        }
        if (picks.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_FEASIBLE_OPTION",
                    "모두가 갈 수 있는 지역을 찾지 못했어요.", Map.of("reason", "NO_REGION"));
        }

        // 5) 저장 + 가입 마감 + 단계 넘기기
        List<UUID> participantIds = completed.stream().map(MemberInputRow::participantId).toList();
        optionsRepository.insert(UUID.randomUUID(), roomId, room.inputVersion(), router.matrixVersion(), now,
                participantIds, slots, picks);
        roomRepository.startPlanning(roomId, now);
        return new Result(optionsRepository.find(roomId).orElseThrow(), true);
    }

    /** 참가자 ID → 가능한 칸들(서울 시각). 예: {참가자1: [1/2 19:00, 1/2 20:00]} */
    private Map<UUID, Set<LocalDateTime>> availabilityOf(List<MemberInputRow> completed) {
        Map<UUID, Set<LocalDateTime>> availability = new LinkedHashMap<>();
        for (MemberInputRow input : completed) {
            availability.put(input.participantId(), inputRepository.findSlots(input.participantId()).stream()
                    .map(s -> LocalDateTime.of(s.date(), LocalTime.of(s.hour(), 0)))
                    .collect(Collectors.toSet()));
        }
        return availability;
    }

    /** 노선도에 있고 카탈로그에서 지원역(selectable)인 역만 후보로 본다. */
    private List<UUID> selectableStationIds() {
        Map<UUID, CatalogStation> catalog = stationRepository.findByIds(router.stationIds());
        return router.stationIds().stream()
                .filter(id -> catalog.containsKey(id) && catalog.get(id).selectable())
                .sorted()
                .toList();
    }

    /** 같은 출발역을 여러 번 계산하지 않게 기억해 둔다. 예: 두 사람이 둘 다 강남 출발이면 한 번만. */
    private Function<UUID, Map<UUID, Route>> cachedRoutes() {
        Map<UUID, Map<UUID, Route>> cache = new HashMap<>();
        return stationId -> cache.computeIfAbsent(stationId, router::routesFrom);
    }
}
