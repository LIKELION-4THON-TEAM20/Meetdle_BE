package com.likelion4thon.Meetdle.room.service;

import com.likelion4thon.Meetdle.common.error.ApiException;
import com.likelion4thon.Meetdle.common.time.ServiceZone;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.contract.RoomMember.Role;
import com.likelion4thon.Meetdle.room.RoomOperationLog;
import com.likelion4thon.Meetdle.room.domain.LockedRoomRow;
import com.likelion4thon.Meetdle.room.domain.NewRoom;
import com.likelion4thon.Meetdle.room.domain.NewRoomMember;
import com.likelion4thon.Meetdle.room.dto.CreateRoomRequest;
import com.likelion4thon.Meetdle.room.dto.CreateRoomResponse;
import com.likelion4thon.Meetdle.room.dto.RoomRef;
import com.likelion4thon.Meetdle.room.invite.InvitationIssuer;
import com.likelion4thon.Meetdle.room.invite.InvitationIssuer.IssuedInvite;
import com.likelion4thon.Meetdle.room.invite.JoinProofStore;
import com.likelion4thon.Meetdle.room.invite.JoinProofStore.JoinGrant;
import com.likelion4thon.Meetdle.room.repository.RoomRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** 방 관련 업무 규칙을 지키는 주방장. */
@Service
public class RoomService {

    static final int MAX_TITLE_LENGTH = 50;
    static final int MAX_DATE_SPAN_DAYS = 7;

    private final RoomRepository roomRepository;
    private final InvitationIssuer invitationIssuer;
    private final JoinProofStore joinProofStore;
    private final Clock clock;

    public RoomService(RoomRepository roomRepository, InvitationIssuer invitationIssuer,
                       JoinProofStore joinProofStore, Clock clock) {
        this.roomRepository = roomRepository;
        this.invitationIssuer = invitationIssuer;
        this.joinProofStore = joinProofStore;
        this.clock = clock;
    }

    /**
     * A07 초대로 가입. 명세 순서대로 검사한다.
     * 1) 증표가 이 세션·이 사람·이 방 것인지 → 아니면 404 INVALID_INVITATION
     * 2) 증표 10분이 지났는지 → 410 INVITATION_EXPIRED
     * 3) 방 행을 잠근 뒤, 이미 회원이면 → 200 (기존 회원 결과. 같은 증표로 다시 눌러도 안전)
     * 4) 추천 계산이 시작됐으면 → 409 JOIN_CLOSED, 정원이 찼으면 → 409 ROOM_FULL
     * 5) 통과하면 MEMBER로 저장 → 201
     */
    @Transactional
    public JoinResult join(AuthenticatedUser user, UUID roomId, String joinProof, HttpSession session) {
        JoinGrant grant = joinProofStore.find(session, joinProof)
                .filter(g -> g.userId().equals(user.userId()) && g.roomId().equals(roomId))
                .orElseThrow(() -> ApiException.of(HttpStatus.NOT_FOUND, "INVALID_INVITATION",
                        "가입 증표가 올바르지 않아요. 초대 링크 또는 코드를 다시 확인해 주세요."));
        if (grant.isExpiredAt(clock.instant())) {
            throw ApiException.of(HttpStatus.GONE, "INVITATION_EXPIRED", "가입 증표 시간이 지났어요. 초대 링크 또는 코드를 다시 확인해 주세요.");
        }

        LockedRoomRow room = roomRepository.lockRoom(roomId)
                .orElseThrow(() -> ApiException.of(HttpStatus.NOT_FOUND, "INVALID_INVITATION", "방을 찾을 수 없어요."));
        if (roomRepository.isMember(roomId, user.userId())) {
            return new JoinResult(new RoomRef(roomId), false);
        }
        if (room.joinClosedAt() != null) {
            throw ApiException.of(HttpStatus.CONFLICT, "JOIN_CLOSED", "방장이 이미 약속 계산을 시작해서 가입할 수 없어요.");
        }
        if (roomRepository.countMembers(roomId) >= room.capacity()) {
            throw ApiException.of(HttpStatus.CONFLICT, "ROOM_FULL", "방 정원이 꽉 찼어요.");
        }
        roomRepository.insertMember(new NewRoomMember(
                UUID.randomUUID(), roomId, user.userId(), user.nickname(), Role.MEMBER));
        return new JoinResult(new RoomRef(roomId), true);
    }

    /** @param created true면 새로 가입(201), false면 원래 회원(200) */
    public record JoinResult(RoomRef room, boolean created) {}

    /**
     * A05 방 만들기. 방 1행 + 방장 참가자 1행 + 초대 1행을 한 트랜잭션에서 저장한다.
     * 중간에 하나라도 실패하면 셋 다 저장되지 않는다(롤백).
     */
    @Transactional
    public CreateRoomResponse createRoom(AuthenticatedUser host, CreateRoomRequest request) {
        String title = normalizeTitle(request.title());
        LocalDate from = request.dates().from();
        LocalDate through = request.dates().through();
        validateDates(from, through);

        NewRoom room = new NewRoom(UUID.randomUUID(), title, from, through, request.capacity());
        roomRepository.insertRoom(room);
        roomRepository.insertMember(new NewRoomMember(
                UUID.randomUUID(), room.roomId(), host.userId(), host.nickname(), Role.HOST));

        // DB의 DATETIME(6)은 마이크로초까지만 저장하므로, 응답과 DB 값이 같도록 미리 잘라둔다.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        IssuedInvite invite = invitationIssuer.issue(room.roomId(), now);
        RoomOperationLog.created(room.roomId(), room.capacity());

        return new CreateRoomResponse(room.roomId(),
                new CreateRoomResponse.Invite(invite.code(), invite.inviteUrl(), invite.expiresAt()));
    }

    /** 제목은 선택값. 앞뒤 공백을 지운 뒤 1~50자여야 한다. 예: "  금요 저녁 " → "금요 저녁" */
    String normalizeTitle(String rawTitle) {
        if (rawTitle == null) {
            return null;
        }
        String title = rawTitle.strip();
        int length = title.codePointCount(0, title.length());
        if (length < 1 || length > MAX_TITLE_LENGTH) {
            throw ApiException.invalidRequest("title", "제목은 1~" + MAX_TITLE_LENGTH + "자여야 해요");
        }
        return title;
    }

    /** 예: 11/20~11/22 → 3일(통과), 11/20~11/27 → 8일(거절), 11/22~11/20 → 거꾸로(거절) */
    void validateDates(LocalDate from, LocalDate through) {
        if (through.isBefore(from)) {
            throw ApiException.invalidRequest("dates", "끝 날짜가 시작 날짜보다 빠를 수 없어요");
        }
        long days = ChronoUnit.DAYS.between(from, through) + 1;
        if (days > MAX_DATE_SPAN_DAYS) {
            throw ApiException.invalidRequest("dates", "날짜 범위는 최대 " + MAX_DATE_SPAN_DAYS + "일이에요");
        }
        // TODO(명세 확인): "지난 날짜 불가"는 명세에 없는 규칙이다. 창환님 확인 후 유지/삭제.
        LocalDate todayInSeoul = LocalDate.now(clock.withZone(ServiceZone.SEOUL));
        if (from.isBefore(todayInSeoul)) {
            throw ApiException.invalidRequest("dates", "지난 날짜로는 방을 만들 수 없어요");
        }
    }
}
