package com.likelion4thon.Meetdle.room.controller;

import com.likelion4thon.Meetdle.common.error.ApiException;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.room.dto.CreateRoomRequest;
import com.likelion4thon.Meetdle.room.dto.CreateRoomResponse;
import com.likelion4thon.Meetdle.room.dto.JoinRoomRequest;
import com.likelion4thon.Meetdle.room.dto.RoomRef;
import com.likelion4thon.Meetdle.room.service.RoomService;
import com.likelion4thon.Meetdle.room.service.RoomService.JoinResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import java.util.UUID;
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
        CreateRoomResponse response = roomService.createRoom(requireLogin(user), request);
        return ResponseEntity.created(URI.create("/api/rooms/" + response.roomId() + "/state")).body(response);
    }

    /**
     * A07 POST /api/rooms/{roomId}/join  본문: {"joinProof":"..."}
     * 새로 가입 → 201 {"roomId":"..."}, 이미 회원 → 200 {"roomId":"..."}
     */
    @PostMapping("/{roomId}/join")
    public ResponseEntity<RoomRef> join(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID roomId,
            @Valid @RequestBody JoinRoomRequest request,
            HttpServletRequest httpRequest
    ) {
        // 세션이 없으면 새로 만들지 않는다 (없으면 당연히 증표도 없음 → 404)
        HttpSession session = httpRequest.getSession(false);
        JoinResult result = roomService.join(requireLogin(user), roomId, request.joinProof(), session);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.room());
    }

    private static AuthenticatedUser requireLogin(AuthenticatedUser user) {
        if (user == null) {
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "로그인이 필요해요.");
        }
        return user;
    }
}
