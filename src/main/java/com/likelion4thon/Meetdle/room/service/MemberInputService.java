package com.likelion4thon.Meetdle.room.service;

import com.likelion4thon.Meetdle.common.error.ApiException;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.planning.station.StationRepository;
import com.likelion4thon.Meetdle.planning.station.StationRepository.CatalogStation;
import com.likelion4thon.Meetdle.room.domain.LockedRoomRow;
import com.likelion4thon.Meetdle.room.domain.MemberInputRow;
import com.likelion4thon.Meetdle.room.dto.HourSlot;
import com.likelion4thon.Meetdle.room.dto.MyInput;
import com.likelion4thon.Meetdle.room.dto.SaveMyInputRequest;
import com.likelion4thon.Meetdle.room.repository.MemberInputRepository;
import com.likelion4thon.Meetdle.room.repository.RoomRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A10 내 조건(출발역·목적지·가능 시간) 저장 또는 완료.
 *
 * 📝 시험 답안지 비유: 작성 중에는 몇 번이고 고쳐 쓸 수 있지만(부분 저장),
 * "제출(complete=true)"을 누르면 더는 못 고친다. 방장이 약속을 처음 확정하면 반 전체 답안지가 걷힌다(방 전체 잠금).
 */
@Service
public class MemberInputService {

    static final int MIN_HOUR = 10;
    static final int MAX_HOUR = 23;
    /** 7일 × 하루 14칸(10~23시) = 98 */
    static final int MAX_SLOTS = 98;

    private final RoomRepository roomRepository;
    private final MemberInputRepository inputRepository;
    private final StationRepository stationRepository;
    private final Clock clock;

    public MemberInputService(RoomRepository roomRepository, MemberInputRepository inputRepository,
                              StationRepository stationRepository, Clock clock) {
        this.roomRepository = roomRepository;
        this.inputRepository = inputRepository;
        this.stationRepository = stationRepository;
        this.clock = clock;
    }

    @Transactional
    public MyInput saveMyInput(AuthenticatedUser user, UUID roomId, SaveMyInputRequest request) {
        // 1) 방 잠금 → 회원인지 확인
        LockedRoomRow room = roomRepository.lockRoom(roomId)
                .orElseThrow(() -> ApiException.of(HttpStatus.NOT_FOUND, "NOT_FOUND", "방을 찾을 수 없어요."));
        UUID participantId = roomRepository.findParticipantId(roomId, user.userId())
                .orElseThrow(() -> ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "이 방의 회원이 아니에요."));

        // 2) 잠금 확인: 방 전체 잠금(첫 확정 뒤) → 본인 완료 뒤 → 버전 비교
        if (room.inputsClosedAt() != null) {
            throw ApiException.of(HttpStatus.CONFLICT, "INPUT_LOCKED", "약속이 확정되어 입력을 바꿀 수 없어요.");
        }
        MemberInputRow current = inputRepository.find(participantId).orElse(MemberInputRow.empty(participantId));
        if (current.completedAt() != null) {
            throw ApiException.of(HttpStatus.CONFLICT, "INPUT_LOCKED", "이미 입력을 완료해서 바꿀 수 없어요.");
        }
        if (request.inputRevision() != current.inputRevision()) {
            // 예: 폰과 노트북에서 동시에 고치다가, 노트북이 먼저 저장해서 버전 3 → 4가 됨. 폰은 아직 3을 들고 있음
            throw new ApiException(HttpStatus.CONFLICT, "STALE_INPUT",
                    "다른 곳에서 먼저 저장했어요. 새로고침 후 다시 시도해 주세요.",
                    Map.of("currentInputRevision", current.inputRevision()));
        }

        // 3) 값 검사
        List<HourSlot> slots = validateSlots(request.slots(), room);
        Map<UUID, CatalogStation> stations = validateStations(request);
        if (request.complete()) {
            requireCompleteInput(request, slots);
        }

        // 4) 바뀐 게 없으면 그대로 돌려준다 (버전도 안 올림)
        List<HourSlot> currentSlots = inputRepository.findSlots(participantId);
        boolean unchanged = !request.complete()
                && Objects.equals(request.originStationId(), current.originStationId())
                && Objects.equals(request.destinationStationId(), current.destinationStationId())
                && slots.equals(currentSlots);
        if (unchanged) {
            return toMyInput(current, currentSlots, stations);
        }

        // 5) 저장: 내 버전 +1, 방 inputVersion +1
        Instant completedAt = request.complete() ? clock.instant().truncatedTo(ChronoUnit.MICROS) : null;
        MemberInputRow saved = new MemberInputRow(participantId, request.originStationId(),
                request.destinationStationId(), current.inputRevision() + 1, completedAt);
        inputRepository.save(saved);
        inputRepository.replaceSlots(participantId, slots);
        roomRepository.bumpInputVersion(roomId);
        return toMyInput(saved, slots, stations);
    }

    /**
     * 시간 칸 검사 후 날짜·시 순서로 정렬.
     * 예: 방 기간 11/20~11/22일 때 {11/23, 19} → 기간 밖, {11/20, 9} → 10시 전, 같은 칸 두 번 → 중복 → 모두 400 INVALID_SLOT
     */
    private static List<HourSlot> validateSlots(List<HourSlot> requested, LockedRoomRow room) {
        if (requested.size() > MAX_SLOTS) {
            throw invalidSlot("시간은 최대 " + MAX_SLOTS + "칸까지 고를 수 있어요");
        }
        Set<HourSlot> seen = new HashSet<>();
        for (HourSlot slot : requested) {
            if (slot.hour() < MIN_HOUR || slot.hour() > MAX_HOUR) {
                throw invalidSlot("시간은 " + MIN_HOUR + "~" + MAX_HOUR + "시만 고를 수 있어요");
            }
            if (slot.date().isBefore(room.startsOn()) || slot.date().isAfter(room.endsOn())) {
                throw invalidSlot("방의 후보 날짜 안에서만 고를 수 있어요");
            }
            if (!seen.add(slot)) {
                throw invalidSlot("같은 시간이 두 번 들어 있어요");
            }
        }
        List<HourSlot> sorted = new ArrayList<>(requested);
        sorted.sort(Comparator.comparing(HourSlot::date).thenComparing(HourSlot::hour));
        return List.copyOf(sorted);
    }

    /** 카탈로그에 없거나 1~9호선 지원역이 아니면 400 UNSUPPORTED_STATION. */
    private Map<UUID, CatalogStation> validateStations(SaveMyInputRequest request) {
        Map<String, UUID> requested = new LinkedHashMap<>();
        if (request.originStationId() != null) {
            requested.put("originStationId", request.originStationId());
        }
        if (request.destinationStationId() != null) {
            requested.put("destinationStationId", request.destinationStationId());
        }
        Map<UUID, CatalogStation> found = stationRepository.findByIds(new HashSet<>(requested.values()));
        requested.forEach((field, stationId) -> {
            CatalogStation station = found.get(stationId);
            if (station == null || !station.selectable()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_STATION",
                        "지원하지 않는 역이에요. 서울 지하철 1~9호선 역을 골라 주세요.", Map.of(field, stationId.toString()));
            }
        });
        return found;
    }

    /** 완료하려면 두 역 + 시간 1칸 이상. 예: 시간을 안 고르고 완료 → 400 INPUT_INCOMPLETE {"missing":["slots"]} */
    private static void requireCompleteInput(SaveMyInputRequest request, List<HourSlot> slots) {
        List<String> missing = new ArrayList<>();
        if (request.originStationId() == null) {
            missing.add("originStationId");
        }
        if (request.destinationStationId() == null) {
            missing.add("destinationStationId");
        }
        if (slots.isEmpty()) {
            missing.add("slots");
        }
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INPUT_INCOMPLETE",
                    "완료하려면 출발역, 목적지, 가능한 시간 1칸 이상이 필요해요.", Map.of("missing", missing));
        }
    }

    private static MyInput toMyInput(MemberInputRow row, List<HourSlot> slots, Map<UUID, CatalogStation> stations) {
        return new MyInput(stationOf(row.originStationId(), stations), stationOf(row.destinationStationId(), stations),
                slots, row.inputRevision(), row.completedAt());
    }

    private static com.likelion4thon.Meetdle.contract.SelectedMeeting.Station stationOf(
            UUID stationId, Map<UUID, CatalogStation> stations) {
        return stationId == null ? null : stations.get(stationId).station();
    }

    private static ApiException invalidSlot(String reason) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SLOT", reason, null);
    }
}
