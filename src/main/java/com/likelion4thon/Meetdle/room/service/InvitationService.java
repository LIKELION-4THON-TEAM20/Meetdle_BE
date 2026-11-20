package com.likelion4thon.Meetdle.room.service;

import com.likelion4thon.Meetdle.common.error.ApiException;
import com.likelion4thon.Meetdle.common.logging.FeatureLog;
import com.likelion4thon.Meetdle.common.logging.FeatureLog.Event;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.room.domain.InvitationRow;
import com.likelion4thon.Meetdle.room.domain.RoomPreviewRow;
import com.likelion4thon.Meetdle.room.dto.DateRange;
import com.likelion4thon.Meetdle.room.dto.InvitePreview;
import com.likelion4thon.Meetdle.room.dto.InvitePreview.EntryStatus;
import com.likelion4thon.Meetdle.room.dto.ParticipantProfile;
import com.likelion4thon.Meetdle.room.dto.ResolveInviteRequest;
import com.likelion4thon.Meetdle.room.dto.ResolvedInvite;
import com.likelion4thon.Meetdle.room.invite.InviteAttemptLimiter;
import com.likelion4thon.Meetdle.room.invite.InviteCrypto;
import com.likelion4thon.Meetdle.room.invite.JoinProofStore;
import com.likelion4thon.Meetdle.room.invite.JoinProofStore.IssuedProof;
import com.likelion4thon.Meetdle.room.repository.RoomInvitationRepository;
import com.likelion4thon.Meetdle.room.repository.RoomRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** 초대 링크 미리보기(A06)와 초대 코드·토큰 확인(A25). 실제 가입(A07)은 RoomService.join. */
@Service
public class InvitationService {

    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern CODE_FORMAT = Pattern.compile("[A-Z0-9]{6}");

    private final RoomInvitationRepository invitationRepository;
    private final RoomRepository roomRepository;
    private final InviteCrypto crypto;
    private final InviteAttemptLimiter limiter;
    private final JoinProofStore joinProofStore;
    private final Clock clock;

    public InvitationService(RoomInvitationRepository invitationRepository, RoomRepository roomRepository,
                             InviteCrypto crypto, InviteAttemptLimiter limiter, JoinProofStore joinProofStore,
                             Clock clock) {
        this.invitationRepository = invitationRepository;
        this.roomRepository = roomRepository;
        this.crypto = crypto;
        this.limiter = limiter;
        this.joinProofStore = joinProofStore;
        this.clock = clock;
    }

    /**
     * A06 링크 미리보기. 로그인 안 한 사람도 볼 수 있다 (viewerUserId = null).
     * 없는 토큰 → 404 INVITATION_NOT_FOUND, 7일 지난 토큰 → 410 INVITATION_EXPIRED
     */
    @Transactional(readOnly = true)
    public InvitePreview preview(String token, UUID viewerUserId) {
        InvitationRow invitation = findByToken(token)
                .orElseThrow(() -> ApiException.of(HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND", "초대를 찾을 수 없어요."));
        if (invitation.isExpiredAt(now())) {
            throw expired();
        }
        return previewOf(invitation.roomId(), viewerUserId);
    }

    /**
     * A25 코드 또는 링크 토큰 확인. 맞으면 미리보기 + 10분짜리 가입 증표를 준다.
     * 예: " k7p2qx " → 공백 제거·대문자 → "K7P2QX"로 찾는다.
     */
    @Transactional(readOnly = true)
    public ResolvedInvite resolve(AuthenticatedUser user, ResolveInviteRequest request, HttpSession session) {
        limiter.checkAllowed(user.userId());

        if (request.code() == null && request.inviteToken() == null) {
            throw ApiException.invalidRequest("code", "code 또는 inviteToken 중 하나는 필요해요");
        }

        Optional<InvitationRow> found;
        if (request.code() != null) {
            String code = request.code().strip().toUpperCase(Locale.ROOT);
            if (!CODE_FORMAT.matcher(code).matches()) {
                throw ApiException.invalidRequest("code", "초대 코드는 영문 대문자·숫자 6자리예요");
            }
            found = invitationRepository.findByCodeLookupHash(crypto.lookupHash(InviteCrypto.Kind.CODE, code))
                    .filter(invitation -> matchesLinkToken(invitation, request.inviteToken()));
        } else {
            found = findByToken(request.inviteToken());
        }
        if (found.isEmpty()) {
            limiter.recordFailure(user.userId());
            throw ApiException.of(HttpStatus.NOT_FOUND, "INVALID_INVITATION", "초대 정보가 올바르지 않아요.");
        }
        InvitationRow invitation = found.get();
        Instant now = now();
        if (invitation.isExpiredAt(now)) {
            throw expired();
        }

        limiter.recordSuccess(user.userId());
        InvitePreview preview = previewOf(invitation.roomId(), user.userId());
        IssuedProof proof = joinProofStore.issue(session, user.userId(), invitation, now);
        FeatureLog.info(Event.INVITATION_RESOLVED, invitation.roomId(), Map.of(
                "userId", user.userId(), "codeProvided", request.code() != null,
                "tokenProvided", request.inviteToken() != null, "entryStatus", preview.entryStatus(),
                "memberCount", preview.joinedCount(), "proofIssued", true));
        return new ResolvedInvite(preview, proof.proof(), proof.expiresAt());
    }

    /** 링크로 들어온 경우(inviteToken 있음) 코드가 그 링크와 같은 초대인지 확인. 홈에서 코드만 넣었으면 통과. */
    private boolean matchesLinkToken(InvitationRow byCode, String inviteToken) {
        if (inviteToken == null) {
            return true;
        }
        return findByToken(inviteToken)
                .map(byToken -> byToken.invitationId().equals(byCode.invitationId()))
                .orElse(false);
    }

    private Optional<InvitationRow> findByToken(String token) {
        if (token == null || !TOKEN_FORMAT.matcher(token).matches()) {
            return Optional.empty();
        }
        return invitationRepository.findByTokenLookupHash(crypto.lookupHash(InviteCrypto.Kind.TOKEN, token));
    }

    private InvitePreview previewOf(UUID roomId, UUID viewerUserId) {
        RoomPreviewRow room = roomRepository.findPreview(roomId)
                .orElseThrow(() -> ApiException.of(HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND", "초대를 찾을 수 없어요."));
        var preview = new InvitePreview(room.roomId(), room.title(), new DateRange(room.startsOn(), room.endsOn()),
                room.hostNickname(), room.joinedCount(), room.capacity(), entryStatus(room, viewerUserId),
                roomRepository.findMemberProfiles(List.of(roomId)).stream()
                        .map(member -> new ParticipantProfile(member.participantId(), member.nickname(), member.profileImageUrl()))
                        .toList());
        FeatureLog.debug(Event.INVITATION_PREVIEW_READ, roomId, Map.of("memberCount", preview.joinedCount(),
                "photoCount", preview.members().stream().filter(member -> member.profileImageUrl() != null).count(),
                "entryStatus", preview.entryStatus(), "authenticatedViewer", viewerUserId != null));
        return preview;
    }

    /** 우선순위: 이미 회원 → 가입 마감 → 정원 꽉 참 → 가입 가능. 예: 4명 정원에 4명 → FULL */
    private EntryStatus entryStatus(RoomPreviewRow room, UUID viewerUserId) {
        if (viewerUserId != null && roomRepository.isMember(room.roomId(), viewerUserId)) {
            return EntryStatus.MEMBER;
        }
        if (room.joinClosedAt() != null) {
            return EntryStatus.CLOSED;
        }
        if (room.joinedCount() >= room.capacity()) {
            return EntryStatus.FULL;
        }
        return EntryStatus.OPEN;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ApiException expired() {
        return ApiException.of(HttpStatus.GONE, "INVITATION_EXPIRED", "초대 기간(7일)이 지났어요.");
    }
}
