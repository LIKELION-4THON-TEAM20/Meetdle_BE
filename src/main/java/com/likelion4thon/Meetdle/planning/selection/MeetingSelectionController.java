package com.likelion4thon.Meetdle.planning.selection;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.likelion4thon.Meetdle.common.error.ApiException;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.contract.SelectedMeeting;
import com.likelion4thon.Meetdle.planning.selection.dto.SelectMeetingRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** SC-06/07 방장이 추천 중 일시·지역을 골라 확정하는 API. */
@Tag(name = "약속 추천")
@RestController
public class MeetingSelectionController {

    private final MeetingSelectionService meetingSelectionService;

    public MeetingSelectionController(MeetingSelectionService meetingSelectionService) {
        this.meetingSelectionService = meetingSelectionService;
    }

    /** A12 PUT /api/rooms/{roomId}/meeting-selection → 200 Meeting */
    @Operation(summary = "약속 일시·지역 확정",
            description = "방장이 추천 응답의 optionsId, slotId, candidateId로 일시와 지역 확정. 장소 등록 시작 전까지 재선택 가능",
            parameters = @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
                    description = "같은 로그인 세션에서 GET /api/me로 받은 csrfToken 사용"),
            responses = {
                @ApiResponse(responseCode = "200", description = "확정된 일시·지역 및 selectionId 반환", useReturnTypeSchema = true)
            })
    @PutMapping("/api/rooms/{roomId}/meeting-selection")
    public SelectedMeeting select(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Parameter(description = "대상 방 UUID. 방 생성 또는 내 방 목록 응답의 roomId 사용")
            @PathVariable UUID roomId,
            @Valid @RequestBody SelectMeetingRequest request
    ) {
        if (user == null) {
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "로그인이 필요해요.");
        }
        return meetingSelectionService.select(user, roomId, request);
    }
}
