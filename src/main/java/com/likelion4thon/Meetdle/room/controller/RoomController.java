package com.likelion4thon.Meetdle.room.controller;

import com.likelion4thon.Meetdle.common.error.ApiException;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.room.dto.CreateRoomRequest;
import com.likelion4thon.Meetdle.room.dto.CreateRoomResponse;
import com.likelion4thon.Meetdle.room.dto.JoinRoomRequest;
import com.likelion4thon.Meetdle.room.dto.MyRoomsPage;
import com.likelion4thon.Meetdle.room.dto.RoomRef;
import com.likelion4thon.Meetdle.room.dto.RoomState;
import com.likelion4thon.Meetdle.room.service.MyRoomsService;
import com.likelion4thon.Meetdle.room.service.RoomService;
import com.likelion4thon.Meetdle.room.service.RoomStateService;
import com.likelion4thon.Meetdle.room.service.RoomService.JoinResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/** 방 관련 HTTP 요청을 받는 홀 직원. 규칙 검사는 서비스에 맡긴다. */
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomService roomService;
    private final MyRoomsService myRoomsService;
    private final RoomStateService roomStateService;

    public RoomController(RoomService roomService, MyRoomsService myRoomsService,
                          RoomStateService roomStateService) {
        this.roomStateService = roomStateService;
        this.roomService = roomService;
        this.myRoomsService = myRoomsService;
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
     * A24 GET /api/rooms?scope=ALL|UPCOMING|PAST&limit=20&cursor=...
     * 예: /api/rooms?scope=UPCOMING&limit=10 → 다가오는 약속 10개 + 다음 페이지 커서
     */
    @GetMapping
    public MyRoomsPage myRooms(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor
    ) {
        return myRoomsService.list(requireLogin(user), scope, limit, cursor);
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

    /** A08 GET /api/rooms/{roomId}/state → 200 RoomState (새로고침·재접속 때 화면 전체 복원) */
    @GetMapping("/{roomId}/state")
    public RoomState state(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID roomId) {
        return roomStateService.read(requireLogin(user), roomId);
    }

    private static AuthenticatedUser requireLogin(AuthenticatedUser user) {
        if (user == null) {
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "로그인이 필요해요.");
        }
        return user;
    }
}
