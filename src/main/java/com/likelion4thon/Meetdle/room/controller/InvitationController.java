package com.likelion4thon.Meetdle.room.controller;

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
    @GetMapping("/{token}")
    public InvitePreview preview(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String token) {
        return invitationService.preview(token, user == null ? null : user.userId());
    }

    /** A25 POST /api/invitations/resolve — 로그인 필요. 본문: {"code":"K7P2QX","inviteToken":"(선택)"} */
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
