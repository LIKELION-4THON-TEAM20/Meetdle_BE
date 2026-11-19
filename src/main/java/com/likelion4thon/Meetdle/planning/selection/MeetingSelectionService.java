package com.likelion4thon.Meetdle.planning.selection;

import com.likelion4thon.Meetdle.common.error.ApiException;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.contract.MeetingWindow;
import com.likelion4thon.Meetdle.contract.RoomMember;
import com.likelion4thon.Meetdle.contract.RoomPhase;
import com.likelion4thon.Meetdle.contract.SelectedMeeting;
import com.likelion4thon.Meetdle.planning.PlanningStateReader.PlanningOptions;
import com.likelion4thon.Meetdle.planning.PlanningStateReader.SlotOption;
import com.likelion4thon.Meetdle.planning.options.PlanningOptionsRepository;
import com.likelion4thon.Meetdle.planning.selection.MeetingSelectionRepository.StoredSelection;
import com.likelion4thon.Meetdle.planning.selection.dto.SelectMeetingRequest;
import com.likelion4thon.Meetdle.room.RoomOperationLog;
import com.likelion4thon.Meetdle.room.domain.LockedRoomRow;
import com.likelion4thon.Meetdle.room.repository.RoomRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A12 일시·지역 확정 또는 재선택 📌
 *
 * 🎫 좌석 예매 비유: 처음 예매(첫 확정)할 때는 "아직 예매 없음(null)"을 확인하고 표를 끊는다.
 * 그 순간 반 전체 답안지(입력)도 걷는다. 장소 등록이 시작되기 전까지는 표를 바꿀 수 있는데,
 * 바꿀 때는 "지금 내 표 번호(selectionId)"를 같이 내야 한다. 다른 기기에서 먼저 바꿨으면 409로 막는다.
 *
 * 순서
 * 1) 방 잠금 → 방장인지
 * 2) 추천 결과가 있고, 단계가 OPTIONS_READY(첫 확정) 또는 REGION_CONFIRMED(재선택)인지
 * 3) 기대한 선택 ID == 지금 선택 ID 인지 (STALE_SELECTION)
 * 4) 고른 일시·지역이 이 방의 추천 결과 안에 있는지, 일시가 아직 안 지났는지 (INVALID_OPTION)
 * 5) 새 선택 행 저장 → 방 포인터 바꾸기 → Meeting 응답
 */
@Service
public class MeetingSelectionService {

    private final RoomRepository roomRepository;
    private final PlanningOptionsRepository optionsRepository;
    private final MeetingSelectionRepository selectionRepository;
    private final Clock clock;

    public MeetingSelectionService(RoomRepository roomRepository, PlanningOptionsRepository optionsRepository,
                                   MeetingSelectionRepository selectionRepository, Clock clock) {
        this.roomRepository = roomRepository;
        this.optionsRepository = optionsRepository;
        this.selectionRepository = selectionRepository;
        this.clock = clock;
    }

    @Transactional
    public SelectedMeeting select(AuthenticatedUser user, UUID roomId, SelectMeetingRequest request) {
        // 1) 방 잠금 → 방장 확인
        LockedRoomRow room = roomRepository.lockRoom(roomId)
                .orElseThrow(() -> ApiException.of(HttpStatus.NOT_FOUND, "NOT_FOUND", "방을 찾을 수 없어요."));
        RoomMember me = roomRepository.findMember(roomId, user.userId())
                .orElseThrow(() -> ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "이 방의 회원이 아니에요."));
        if (me.role() != RoomMember.Role.HOST) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "방장만 일시·지역을 확정할 수 있어요.");
        }

        // 2) 단계 확인
        PlanningOptions options = optionsRepository.find(roomId)
                .orElseThrow(() -> ApiException.of(HttpStatus.CONFLICT, "WRONG_PHASE", "추천 계산을 먼저 해 주세요."));
        if (room.phase() != RoomPhase.OPTIONS_READY && room.phase() != RoomPhase.REGION_CONFIRMED) {
            throw ApiException.of(HttpStatus.CONFLICT, "WRONG_PHASE", "장소 등록이 시작돼서 일시·지역을 바꿀 수 없어요.");
        }

        // 3) 기대값 확인. 예: 내 화면은 "확정 없음(null)"인데 다른 기기에서 이미 확정함 → 409
        if (!Objects.equals(request.expectedSelectionId(), room.currentSelectionId())) {
            Map<String, Object> details = new HashMap<>();
            details.put("currentSelectionId", room.currentSelectionId());
            throw new ApiException(HttpStatus.CONFLICT, "STALE_SELECTION",
                    "그사이 확정 내용이 바뀌었어요. 새로고침 후 다시 시도해 주세요.", details);
        }

        // 4) 이 방 추천 결과 안의 일시·지역인지
        if (!request.optionsId().equals(options.optionsId())) {
            throw invalidOption("optionsId");
        }
        SlotOption slot = options.slots().stream()
                .filter(s -> s.slotId().equals(request.slotId()))
                .findFirst()
                .orElseThrow(() -> invalidOption("slotId"));
        boolean candidateExists = options.regions().stream()
                .anyMatch(r -> r.candidateId().equals(request.candidateId()));
        if (!candidateExists) {
            throw invalidOption("candidateId");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!slot.meetingAt().isAfter(now)) {
            // 예: 19시 칸을 19시 10분에 확정하려고 함
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_OPTION", "이미 지난 시간이에요.",
                    Map.of("slotId", "PASSED"));
        }
        if (now.isAfter(MeetingWindow.calculate(slot.meetingAt(), now).registrationCutoff())) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_OPTION", "약속까지 3시간 이상 남은 일시를 선택해 주세요.",
                    Map.of("slotId", "TOO_CLOSE"));
        }

        // 같은 일시·지역을 다시 보내면 새로 만들지 않고 지금 선택을 그대로 돌려준다 (버튼 두 번 눌림 대비)
        if (room.currentSelectionId() != null) {
            StoredSelection current = selectionRepository.find(room.currentSelectionId()).orElseThrow();
            if (current.slotId().equals(request.slotId()) && current.candidateId().equals(request.candidateId())) {
                return current.meeting();
            }
        }

        // 5) 저장 + 포인터 이동 (첫 확정이면 입력 잠금까지)
        UUID selectionId = UUID.randomUUID();
        selectionRepository.insert(selectionId, roomId, options.optionsId(), request.slotId(),
                request.candidateId(), now);
        roomRepository.confirmSelection(roomId, selectionId, now);
        RoomOperationLog.phaseChanged(roomId, room.phase(), RoomPhase.REGION_CONFIRMED);
        return selectionRepository.find(selectionId).orElseThrow().meeting();
    }

    private static ApiException invalidOption(String field) {
        return new ApiException(HttpStatus.CONFLICT, "INVALID_OPTION",
                "이 방의 추천 결과에 없는 선택이에요.", Map.of(field, "NOT_IN_OPTIONS"));
    }
}
