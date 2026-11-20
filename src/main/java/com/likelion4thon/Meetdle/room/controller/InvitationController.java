package com.likelion4thon.Meetdle.room.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.likelion4thon.Meetdle.common.error.ApiException;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.room.dto.InvitePreview;
import com.likelion4thon.Meetdle.room.dto.ResolveInviteRequest;
import com.likelion4thon.Meetdle.room.dto.ResolvedInvite;
import com.likelion4thon.Meetdle.room.service.InvitationService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 초대 링크·코드 관련 요청. 실제 가입은 RoomController의 A07. */
@Tag(name = "초대")
@RestController
@RequestMapping("/api/invitations")
public class InvitationController {

    private final InvitationService invitationService;

    public InvitationController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    /**
     * A06 GET /api/invitations/{token} — 공개(로그인 없이 호출 가능).
     * 로그인한 사람이 부르면 entryStatus가 MEMBER일 수 있다.
     * ⚠️ 운영에서는 창환님 SecurityConfiguration에서 이 경로를 permitAll로 열어줘야 한다.
     */
    @Operation(summary = "초대 링크 미리보기",
            description = "초대 URL의 토큰으로 방 제목·초대 상태·현재 참여자의 ID·닉네임·프로필 사진 조회",
            responses = {
                @ApiResponse(responseCode = "200", description = "초대 미리보기 반환", useReturnTypeSchema = true)
            })
    @GetMapping("/{token}")
    public InvitePreview preview(@AuthenticationPrincipal AuthenticatedUser user,
            @Parameter(description = "초대 URL의 경로 끝에 있는 초대 토큰")
            @PathVariable String token) {
        return invitationService.preview(token, user == null ? null : user.userId());
    }

    /** A25 POST /api/invitations/resolve — 로그인 필요. code 또는 inviteToken 중 하나 이상. */
    @Operation(summary = "초대 코드 또는 링크 토큰 확인",
            description = "code 또는 inviteToken만으로 검증 후 joinProof와 현재 참여자 프로필을 포함한 preview 반환. 둘 다 보내면 같은 초대인지 확인",
            parameters = @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
                    description = "같은 로그인 세션에서 GET /api/me로 받은 csrfToken 사용"),
            responses = {
                @ApiResponse(responseCode = "200", description = "초대 미리보기와 참여 증표 반환", useReturnTypeSchema = true)
            })
    @PostMapping("/resolve")
    public ResolvedInvite resolve(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ResolveInviteRequest request,
            HttpSession session
    ) {
        if (user == null) {
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "로그인이 필요해요.");
        }
        return invitationService.resolve(user, request, session);
    }
}
