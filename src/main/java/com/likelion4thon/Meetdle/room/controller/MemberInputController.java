package com.likelion4thon.Meetdle.room.controller;

import com.likelion4thon.Meetdle.common.error.ApiException;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.room.dto.MyInput;
import com.likelion4thon.Meetdle.room.dto.SaveMyInputRequest;
import com.likelion4thon.Meetdle.room.service.MemberInputService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** SC-04 시간·출발역·목적지 입력 화면이 부르는 API. */
@RestController
public class MemberInputController {

    private final MemberInputService memberInputService;

    public MemberInputController(MemberInputService memberInputService) {
        this.memberInputService = memberInputService;
    }

    /** A10 PUT /api/rooms/{roomId}/inputs/me → 200 MyInput */
    @PutMapping("/api/rooms/{roomId}/inputs/me")
    public MyInput saveMyInput(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID roomId,
            @Valid @RequestBody SaveMyInputRequest request
    ) {
        if (user == null) {
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "로그인이 필요해요.");
        }
        return memberInputService.saveMyInput(user, roomId, request);
    }
}
