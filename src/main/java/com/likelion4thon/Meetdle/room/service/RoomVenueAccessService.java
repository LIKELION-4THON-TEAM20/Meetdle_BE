package com.likelion4thon.Meetdle.room.service;

import com.likelion4thon.Meetdle.common.error.ApiException;
import com.likelion4thon.Meetdle.contract.RoomMember;
import com.likelion4thon.Meetdle.contract.RoomPhase;
import com.likelion4thon.Meetdle.contract.RoomVenueAccess;
import com.likelion4thon.Meetdle.planning.MeetingQuery;
import com.likelion4thon.Meetdle.room.domain.LockedRoomRow;
import com.likelion4thon.Meetdle.room.repository.RoomRepository;
import com.likelion4thon.Meetdle.room.repository.RoomRepository.MemberStatusRow;
import com.likelion4thon.Meetdle.room.repository.RoomRepository.RoomStateRow;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 창환님(B) 장소 코드가 쓰는 "방 창구" 🚪 (contract.RoomVenueAccess 구현)
 *
 * 🏫 교무실 비유: 장소 쪽은 방 테이블을 직접 만지지 않고, 이 창구에 와서
 * 1) read: "이 방 지금 어때요?" → 단계·정원·회원 명단·확정 역/시각을 사진 한 장(snapshot)으로 받음
 * 2) lockForWrite: "이 방 잠깐 잠가 주세요" → 방 행을 잠그고(FOR UPDATE) 그 순간 사진을 줌
 * 3) recordChange: "장소 등록 시작했어요" → 단계를 바꾸고 revision +1
 * 이렇게 하면 방 단계 규칙(RoomPhase.canTransitionTo)을 방 쪽(A)에서 한 군데로 지킬 수 있다.
 */
@Service
public class RoomVenueAccessService implements RoomVenueAccess {

    private final RoomRepository roomRepository;
    private final MeetingQuery meetingQuery;
    private final Clock clock;

    public RoomVenueAccessService(RoomRepository roomRepository, MeetingQuery meetingQuery, Clock clock) {
        this.roomRepository = roomRepository;
        this.meetingQuery = meetingQuery;
        this.clock = clock;
    }

    /** 회원만 읽을 수 있다. 회원이 아니면 403, 방이 없으면 404. */
    @Override
    @Transactional(readOnly = true)
    public RoomVenueSnapshot read(UUID roomId, UUID userId) {
        RoomStateRow room = roomRepository.findStateRow(roomId)
                .orElseThrow(() -> ApiException.of(HttpStatus.NOT_FOUND, "NOT_FOUND", "방을 찾을 수 없어요."));
        List<MemberStatusRow> members = roomRepository.findMemberStatuses(roomId);
        Optional<RoomMember> actor = memberOf(members, userId);
        if (actor.isEmpty()) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "이 방의 회원이 아니에요.");
        }
        return new RoomVenueSnapshot(roomId, room.phase(), room.revision(), room.capacity(), actor,
                participantIds(members), meetingQuery.find(roomId), now());
    }

    /**
     * 부르는 쪽 트랜잭션 안에서 방 행을 잠근다. 트랜잭션 밖이면 잠금이 바로 풀리므로 막는다.
     * 회원이 아닌 사용자나 시스템(마감 스케줄러)이면 actorMember가 비어 있다 → 권한 판단은 장소 쪽이 한다.
     */
    @Override
    public LockedRoomVenue lockForWrite(UUID roomId, Actor actor) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("lockForWrite는 쓰기 트랜잭션 안에서 불러야 해요.");
        }
        LockedRoomRow room = roomRepository.lockRoom(roomId)
                .orElseThrow(() -> ApiException.of(HttpStatus.NOT_FOUND, "NOT_FOUND", "방을 찾을 수 없어요."));
        List<MemberStatusRow> members = roomRepository.findMemberStatuses(roomId);
        Optional<RoomMember> actorMember = actor instanceof UserActor user
                ? memberOf(members, user.userId())
                : Optional.empty();
        RoomVenueSnapshot snapshot = new RoomVenueSnapshot(roomId, room.phase(), room.revision(), room.capacity(),
                actorMember, participantIds(members), meetingQuery.find(roomId), now());
        return new Lock(snapshot);
    }

    /**
     * 장소 쪽 변경을 기록한다. 단계가 바뀌면 규칙(canTransitionTo)을 확인한다.
     * 예: REGION_CONFIRMED → PLACE_SUBMISSION_OPEN (OK), REGION_CONFIRMED → VOTING_OPEN (규칙 위반 → 오류)
     */
    @Override
    public void recordChange(LockedRoomVenue context, RoomPhase nextPhase) {
        if (!(context instanceof Lock lock)) {
            throw new IllegalArgumentException("lockForWrite로 받은 잠금만 쓸 수 있어요.");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("recordChange는 잠금을 받은 트랜잭션 안에서 불러야 해요.");
        }
        RoomPhase current = lock.phase;
        if (nextPhase != current && !current.canTransitionTo(nextPhase)) {
            throw new IllegalStateException("방 단계를 " + current + "에서 " + nextPhase + "로 바꿀 수 없어요.");
        }
        roomRepository.recordPhaseChange(lock.snapshot.roomId(), nextPhase);
        lock.phase = nextPhase;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static Optional<RoomMember> memberOf(List<MemberStatusRow> members, UUID userId) {
        return members.stream()
                .filter(m -> m.userId().equals(userId))
                .findFirst()
                .map(m -> new RoomMember(m.participantId(), m.role()));
    }

    private static Set<UUID> participantIds(List<MemberStatusRow> members) {
        return members.stream().map(MemberStatusRow::participantId).collect(Collectors.toSet());
    }

    /** 잠금 손잡이. snapshot은 잠근 순간 사진이고, phase는 recordChange 뒤 현재 단계를 따라간다. */
    private static final class Lock implements LockedRoomVenue {
        private final RoomVenueSnapshot snapshot;
        private RoomPhase phase;

        private Lock(RoomVenueSnapshot snapshot) {
            this.snapshot = snapshot;
            this.phase = snapshot.phase();
        }

        @Override
        public RoomVenueSnapshot snapshot() {
            return snapshot;
        }
    }
}
