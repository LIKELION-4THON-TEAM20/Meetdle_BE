package com.likelion4thon.Meetdle.room.controller;

import com.likelion4thon.Meetdle.common.error.ApiException;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.room.dto.CreateRoomRequest;
import com.likelion4thon.Meetdle.room.dto.CreateRoomResponse;
import com.likelion4thon.Meetdle.room.service.RoomService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** 방 관련 HTTP 요청을 받는 홀 직원. 규칙 검사는 RoomService에 맡긴다. */
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    /**
     * A05 POST /api/rooms → 201 Created
     * 본문: {"roomId":"...","invite":{"code":"K7P2QX","inviteUrl":"...","expiresAt":"..."}}
     * Location 헤더(/api/rooms/{roomId}/state)는 명세에 없지만 편의로 함께 준다.
     *
     * TODO(멱등성): Idempotency-Key 헤더 처리는 CommandDedup 구현 후 붙인다.
     */
    @PostMapping
    public ResponseEntity<CreateRoomResponse> createRoom(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateRoomRequest request
    ) {
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "로그인이 필요해요.", null);
        }
        CreateRoomResponse response = roomService.createRoom(user, request);
        return ResponseEntity.created(URI.create("/api/rooms/" + response.roomId() + "/state")).body(response);
    }
}
