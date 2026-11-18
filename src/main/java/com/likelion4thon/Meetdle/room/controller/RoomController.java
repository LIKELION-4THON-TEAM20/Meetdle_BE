package com.likelion4thon.Meetdle.room.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "약속 방")
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
    @Operation(summary = "약속 방 생성",
            description = "제목, 후보 날짜 범위, 방장 포함 정원을 보내 새 약속 방 생성",
            parameters = @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
                    description = "같은 로그인 세션에서 GET /api/me로 받은 csrfToken 사용"),
            responses = {
                @ApiResponse(responseCode = "201", description = "방 ID와 초대 코드·링크 반환", useReturnTypeSchema = true)
            })
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
    @Operation(summary = "내 약속 방 목록 조회",
            description = "내가 참여한 방을 날짜 범위로 조회. 다음 페이지는 응답의 nextCursor를 같은 scope와 함께 전달",
            responses = {
                @ApiResponse(responseCode = "200", description = "방 목록과 다음 페이지 커서 반환", useReturnTypeSchema = true)
            })
    @GetMapping
    public MyRoomsPage myRooms(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Parameter(description = "조회 범위. ALL은 전체, UPCOMING은 예정, PAST는 지난 방. 기본값 ALL", example = "ALL")
            @RequestParam(required = false) String scope,
            @Parameter(description = "페이지당 최대 방 수. 1~50 지정, 기본값 20", example = "20")
            @RequestParam(required = false) Integer limit,
            @Parameter(description = "다음 페이지 조회 시 직전 응답의 nextCursor 사용. 첫 페이지는 생략")
            @RequestParam(required = false) String cursor
    ) {
        return myRoomsService.list(requireLogin(user), scope, limit, cursor);
    }

    /**
     * A07 POST /api/rooms/{roomId}/join  본문: {"joinProof":"..."}
     * 새로 가입 → 201 {"roomId":"..."}, 이미 회원 → 200 {"roomId":"..."}
     */
    @Operation(summary = "약속 방 참여",
            description = "초대 코드 확인에서 받은 joinProof를 같은 로그인 세션으로 보내 해당 방에 참여",
            parameters = @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
                    description = "같은 로그인 세션에서 GET /api/me로 받은 csrfToken 사용"),
            responses = {
                @ApiResponse(responseCode = "201", description = "새로 참여한 방의 ID 반환", useReturnTypeSchema = true),
                @ApiResponse(responseCode = "200", description = "이미 참여 중인 방의 ID 반환", useReturnTypeSchema = true)
            })
    @PostMapping("/{roomId}/join")
    public ResponseEntity<RoomRef> join(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Parameter(description = "대상 방 UUID. 방 생성 또는 내 방 목록 응답의 roomId 사용")
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
    @Operation(summary = "약속 방 상태 조회",
            description = "참여 중인 방의 현재 단계와 가능 행동, 내 입력, 추천·투표·결과 조회. 다음 쓰기 요청에 필요한 라운드와 버전 확인",
            responses = {
                @ApiResponse(responseCode = "200", description = "현재 방 상태 반환", useReturnTypeSchema = true)
            })
    @GetMapping("/{roomId}/state")
    public RoomState state(@AuthenticationPrincipal AuthenticatedUser user,
            @Parameter(description = "대상 방 UUID. 방 생성 또는 내 방 목록 응답의 roomId 사용")
            @PathVariable UUID roomId) {
        return roomStateService.read(requireLogin(user), roomId);
    }

    private static AuthenticatedUser requireLogin(AuthenticatedUser user) {
        if (user == null) {
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "로그인이 필요해요.");
        }
        return user;
    }
}
