package com.likelion4thon.Meetdle.room.controller;

import com.likelion4thon.Meetdle.common.error.ApiException;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.contract.RoomMember;
import com.likelion4thon.Meetdle.contract.RoomPhase;
import com.likelion4thon.Meetdle.room.dto.RoomState;
import com.likelion4thon.Meetdle.room.service.RoomStateService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RoomShareController {
    private final RoomStateService states;

    public RoomShareController(RoomStateService states) {
        this.states = states;
    }

    public enum Purpose { INPUT, REGISTRATION, VOTING, HOST, RESULT }
    public record Message(String id, String text) {}
    public record Recipient(UUID participantId, String nickname, String profileImageUrl) {}
    public record Share(Purpose purpose, String title, String shareUrl, List<Message> messages,
                        List<Recipient> recipients) {}

    @Operation(summary = "카카오 공유·재촉 메시지 준비", description = "회원 복귀 링크, 메시지 선택지, 대상 회원 목록 반환")
    @GetMapping("/api/rooms/{roomId}/share")
    public Share share(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID roomId,
                       @RequestParam Purpose purpose) {
        var state = states.read(user, roomId);
        List<UUID> targets;
        String message;
        switch (purpose) {
            case INPUT -> {
                require(state.phase() == RoomPhase.WAITING_FOR_DEPARTURE);
                targets = state.members().stream().filter(value -> !value.inputComplete())
                        .map(RoomState.MemberStatus::participantId).toList();
                message = "가능한 시간과 이동 정보를 입력해주세요.";
            }
            case REGISTRATION -> {
                require(state.registration() != null && state.phase() == RoomPhase.PLACE_SUBMISSION_OPEN);
                targets = state.registration().completion().stream().filter(value -> !value.completed())
                        .map(RoomState.CompletionEntry::participantId).toList();
                message = "가고 싶은 장소를 등록해주세요.";
            }
            case VOTING -> {
                require(state.voting() != null);
                targets = state.voting().completion().stream().filter(value -> !value.completed())
                        .map(RoomState.CompletionEntry::participantId).toList();
                message = "장소 후보에 투표해주세요.";
            }
            case HOST -> {
                require(state.me().role() != RoomMember.Role.HOST);
                targets = state.members().stream().filter(value -> value.role() == RoomMember.Role.HOST)
                        .map(RoomState.MemberStatus::participantId).toList();
                message = "약속방 진행 현황을 확인해주세요. 다음 단계로 진행할 수 있는지 확인해주세요.";
            }
            case RESULT -> {
                require(state.phase() == RoomPhase.FINAL_PLACE_CONFIRMED && state.result() != null);
                targets = state.members().stream().map(RoomState.MemberStatus::participantId).toList();
                message = "만남 일정과 방문할 장소가 확정됐어요. 약속방에서 확인해주세요.";
            }
            default -> throw new IllegalStateException("Unknown share purpose");
        }
        var recipients = state.members().stream()
                .filter(value -> targets.contains(value.participantId()) && !value.participantId().equals(state.me().participantId()))
                .map(value -> new Recipient(value.participantId(), value.nickname(), value.profileImageUrl())).toList();
        return new Share(purpose, state.title(), state.shareUrl(),
                List.of(new Message("DEFAULT", message), new Message("FRIENDLY", "친구들, " + message)), recipients);
    }

    private static void require(boolean allowed) {
        if (!allowed) {
            throw ApiException.of(HttpStatus.CONFLICT, "WRONG_PHASE", "현재 단계에서 사용할 수 없는 공유예요.");
        }
    }
}
