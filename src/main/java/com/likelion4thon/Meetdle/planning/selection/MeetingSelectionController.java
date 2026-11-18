package com.likelion4thon.Meetdle.planning.selection;

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
@RestController
public class MeetingSelectionController {

    private final MeetingSelectionService meetingSelectionService;

    public MeetingSelectionController(MeetingSelectionService meetingSelectionService) {
        this.meetingSelectionService = meetingSelectionService;
    }

    /** A12 PUT /api/rooms/{roomId}/meeting-selection → 200 Meeting */
    @PutMapping("/api/rooms/{roomId}/meeting-selection")
    public SelectedMeeting select(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID roomId,
            @Valid @RequestBody SelectMeetingRequest request
    ) {
        if (user == null) {
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "로그인이 필요해요.");
        }
        return meetingSelectionService.select(user, roomId, request);
    }
}
