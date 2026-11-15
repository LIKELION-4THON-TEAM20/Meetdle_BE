package com.likelion4thon.Meetdle.room.service;

import com.likelion4thon.Meetdle.common.error.ApiException;
import com.likelion4thon.Meetdle.common.time.ServiceZone;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.contract.RoomMember.Role;
import com.likelion4thon.Meetdle.room.domain.NewRoom;
import com.likelion4thon.Meetdle.room.domain.NewRoomMember;
import com.likelion4thon.Meetdle.room.dto.CreateRoomRequest;
import com.likelion4thon.Meetdle.room.dto.CreateRoomResponse;
import com.likelion4thon.Meetdle.room.invite.InvitationIssuer;
import com.likelion4thon.Meetdle.room.invite.InvitationIssuer.IssuedInvite;
import com.likelion4thon.Meetdle.room.repository.RoomRepository;
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
    private final Clock clock;

    public RoomService(RoomRepository roomRepository, InvitationIssuer invitationIssuer, Clock clock) {
        this.roomRepository = roomRepository;
        this.invitationIssuer = invitationIssuer;
        this.clock = clock;
    }

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

        return new CreateRoomResponse(room.roomId(),
                new CreateRoomResponse.Invite(invite.code(), invite.inviteUrl(), invite.expiresAt()));
    }

    /** 제목은 선택값. 앞뒤 공백을 지운 뒤 1~50자여야 한다. 예: "  금요 저녁 " → "금요 저녁" */
    private String normalizeTitle(String rawTitle) {
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
    private void validateDates(LocalDate from, LocalDate through) {
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
