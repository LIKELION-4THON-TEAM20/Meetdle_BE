package com.likelion4thon.Meetdle.room.service;

import com.likelion4thon.Meetdle.common.error.ApiException;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.contract.MeetingWindow;
import com.likelion4thon.Meetdle.contract.RoomMember;
import com.likelion4thon.Meetdle.contract.RoomPhase;
import com.likelion4thon.Meetdle.contract.SelectedMeeting;
import com.likelion4thon.Meetdle.contract.VenueStateReader;
import com.likelion4thon.Meetdle.contract.VenueStateReader.VenueState;
import com.likelion4thon.Meetdle.planning.MeetingQuery;
import com.likelion4thon.Meetdle.planning.PlanningStateReader;
import com.likelion4thon.Meetdle.planning.options.dto.PlanningOptionsResponse;
import com.likelion4thon.Meetdle.room.dto.CreateRoomResponse;
import com.likelion4thon.Meetdle.room.dto.DateRange;
import com.likelion4thon.Meetdle.room.dto.MyInput;
import com.likelion4thon.Meetdle.room.dto.RoomState;
import com.likelion4thon.Meetdle.room.invite.InvitationIssuer;
import com.likelion4thon.Meetdle.room.invite.InviteCrypto;
import com.likelion4thon.Meetdle.room.repository.RoomInvitationRepository;
import com.likelion4thon.Meetdle.room.repository.RoomRepository;
import com.likelion4thon.Meetdle.room.repository.RoomRepository.MemberStatusRow;
import com.likelion4thon.Meetdle.room.repository.RoomRepository.RoomStateRow;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A08 약속방 상태 복원 🧩
 *
 * 📺 TV 리모컨 "현재 화면 다시 그리기" 비유: 새로고침하거나 앱을 다시 열면 이 API 한 번으로
 * "지금 몇 단계인지 + 내 입력 + 추천 결과 + 확정 + 장소 등록·투표 상황 + 지금 누를 수 있는 버튼"을 다 받는다.
 *
 * 조립 순서 (한 읽기 트랜잭션 = 같은 순간의 사진)
 * 1) 방 + 회원 목록 → 회원이 아니면 403
 * 2) A 조각: 내 조건, 추천 결과(A11), 확정(A12), 초대(방장만)
 * 3) B 조각: 창환님 VenueStateReader가 장소 등록·투표·결과를 준다 → 명세 모양으로 옮겨 담는다
 *    (B 코드가 아직 합쳐지기 전이면 장소 칸은 전부 null)
 * 4) allowedActions = A 버튼(입력·계산·확정) + B 버튼(장소·투표)
 */
@Service
public class RoomStateService {

    private final RoomRepository roomRepository;
    private final RoomInvitationRepository invitationRepository;
    private final MemberInputService memberInputService;
    private final PlanningStateReader planningStateReader;
    private final MeetingQuery meetingQuery;
    private final ObjectProvider<VenueStateReader> venueStateReader;
    private final InviteCrypto inviteCrypto;
    private final InvitationIssuer invitationIssuer;
    private final Clock clock;
    @Value("${meetdle.frontend.origin:http://localhost:5173}")
    private String frontendOrigin;

    public RoomStateService(RoomRepository roomRepository, RoomInvitationRepository invitationRepository,
                            MemberInputService memberInputService, PlanningStateReader planningStateReader,
                            MeetingQuery meetingQuery, ObjectProvider<VenueStateReader> venueStateReader,
                            InviteCrypto inviteCrypto, InvitationIssuer invitationIssuer, Clock clock) {
        this.roomRepository = roomRepository;
        this.invitationRepository = invitationRepository;
        this.memberInputService = memberInputService;
        this.planningStateReader = planningStateReader;
        this.meetingQuery = meetingQuery;
        this.venueStateReader = venueStateReader;
        this.inviteCrypto = inviteCrypto;
        this.invitationIssuer = invitationIssuer;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public RoomState read(AuthenticatedUser user, UUID roomId) {
        // 1) 방 + 회원
        RoomStateRow room = roomRepository.findStateRow(roomId)
                .orElseThrow(() -> ApiException.of(HttpStatus.NOT_FOUND, "NOT_FOUND", "방을 찾을 수 없어요."));
        List<MemberStatusRow> members = roomRepository.findMemberStatuses(roomId);
        MemberStatusRow me = members.stream()
                .filter(m -> m.userId().equals(user.userId()))
                .findFirst()
                .orElseThrow(() -> ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "이 방의 회원이 아니에요."));
        boolean host = me.role() == RoomMember.Role.HOST;
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        // 2) A 조각
        MyInput myInput = memberInputService.readMyInput(me.participantId());
        Optional<PlanningStateReader.PlanningOptions> options = planningStateReader.findOptions(roomId);
        Optional<SelectedMeeting> meeting = meetingQuery.find(roomId);
        int inputCompleted = (int) members.stream().filter(MemberStatusRow::inputComplete).count();

        // 3) B 조각
        VenueStateReader venue = venueStateReader.getIfAvailable();
        VenueState venueState = venue == null
                ? new VenueState(null, null, null, null, null, null, List.of())
                : venue.project(new VenueStateReader.ViewContext(roomId,
                        new RoomMember(me.participantId(), me.role()), room.phase(), room.capacity(),
                        meeting.map(SelectedMeeting::meetingAt), now));

        // 4) 버튼
        List<String> actions = new ArrayList<>(roomActions(room, host, myInput, options.isPresent(), inputCompleted));
        if (host) {
            actions.add("EDIT_ROOM");
            if (room.phase() == RoomPhase.WAITING_FOR_DEPARTURE && room.startedAt() == null && members.size() >= 2) {
                actions.add("START_ROOM");
            }
        }
        boolean past = room.phase() == RoomPhase.ENDED
                || meeting.map(value -> !value.endsAt().isAfter(now)).orElseGet(() ->
                    room.endsOn().isBefore(java.time.LocalDate.ofInstant(now, com.likelion4thon.Meetdle.common.time.ServiceZone.SEOUL)));
        if (past) {
            actions.add("DELETE_ROOM");
        }
        venueState.allowedActions().forEach(action -> actions.add(action.name()));
        Set<VenueStateReader.Action> venueActions = new HashSet<>(venueState.allowedActions());

        return new RoomState(
                room.roomId(),
                room.title(),
                new DateRange(room.startsOn(), room.endsOn()),
                room.phase(),
                room.revision(),
                room.inputVersion(),
                now,
                members.size(),
                room.capacity(),
                new RoomState.Progress(inputCompleted, room.capacity()),
                new RoomState.MyMember(me.participantId(), me.role(),
                        venueActions.contains(VenueStateReader.Action.EDIT_NOMINATION),
                        venueActions.contains(VenueStateReader.Action.EDIT_PREFERENCES)
                                || venueActions.contains(VenueStateReader.Action.SUBMIT_VOTE)),
                members.stream()
                        .map(m -> new RoomState.MemberStatus(m.participantId(), m.nickname(), m.role(),
                                m.inputComplete(), completionStatus(m.inputComplete(), m.inputStarted()), m.profileImageUrl()))
                        .toList(),
                myInput,
                host && room.joinClosedAt() == null ? invite(roomId) : null,
                options.map(o -> PlanningOptionsResponse.from(host ? o : withoutTravel(o))).orElse(null),
                meeting.orElse(null),
                registration(venueState.registration(), members, me, room.capacity()),
                voting(venueState.voting(), members, room.capacity()),
                voteReview(venueState.voteReview(), members, room.capacity()),
                tieDecision(venueState.tieDecision()),
                finalResult(venueState.result(), meeting.orElse(null)),
                venueState.recovery(),
                actions, room.startedAt(), frontendOrigin + "/rooms/" + roomId,
                meeting.map(value -> {
                    var window = MeetingWindow.calculate(value.meetingAt(), now);
                    return new RoomState.DeadlinePolicy((int) window.registrationMinimum().toMinutes(),
                            window.registrationCutoff(), (int) window.votingMinimum().toMinutes(), window.votingCutoff());
                }).orElse(null));
    }

    /**
     * A가 정하는 버튼.
     * EDIT_INPUT·COMPLETE_INPUT: 첫 확정 전이고 내가 아직 완료 안 했을 때
     * CALCULATE_OPTIONS: 방장, 계산 전, 입력 완료 2명 이상
     * SELECT_MEETING: 방장, 추천 결과 있음, 장소 등록 시작 전 (첫 확정 또는 재선택)
     */
    static List<String> roomActions(RoomStateRow room, boolean host, MyInput myInput, boolean hasOptions,
                                    int inputCompleted) {
        List<String> actions = new ArrayList<>();
        boolean beforeConfirm = room.phase() == RoomPhase.WAITING_FOR_DEPARTURE
                || room.phase() == RoomPhase.OPTIONS_READY;
        if (beforeConfirm && room.inputsClosedAt() == null && myInput.completedAt() == null) {
            actions.add("EDIT_INPUT");
            actions.add("COMPLETE_INPUT");
        }
        if (host && room.phase() == RoomPhase.WAITING_FOR_DEPARTURE && !hasOptions && inputCompleted >= 2) {
            actions.add("CALCULATE_OPTIONS");
        }
        if (host && hasOptions
                && (room.phase() == RoomPhase.OPTIONS_READY || room.phase() == RoomPhase.REGION_CONFIRMED)) {
            actions.add("SELECT_MEETING");
        }
        return actions;
    }

    /** 방장만 개인별 이동시간(travel)을 본다. 회원에게는 빈 목록. */
    private static PlanningStateReader.PlanningOptions withoutTravel(PlanningStateReader.PlanningOptions options) {
        return new PlanningStateReader.PlanningOptions(options.optionsId(), options.inputVersion(),
                options.matrixVersion(), options.slots(),
                options.regions().stream()
                        .map(r -> new PlanningStateReader.RegionCandidate(r.candidateId(), r.station(),
                                r.meanMinutes(), r.maxMinutes(), r.meanTransfers(), r.reasons(), r.score(),
                                List.of()))
                        .toList());
    }

    /** 방장 화면에 초대 코드·링크를 다시 보여준다. 가입 마감(첫 계산) 뒤에는 안 보여준다. */
    private CreateRoomResponse.Invite invite(UUID roomId) {
        return invitationRepository.findStoredByRoom(roomId)
                .map(stored -> new CreateRoomResponse.Invite(
                        inviteCrypto.decrypt(stored.encryptedCode()),
                        invitationIssuer.inviteUrl(inviteCrypto.decrypt(stored.encryptedToken())),
                        stored.expiresAt()))
                .orElse(null);
    }

    private static List<RoomState.CompletionEntry> completion(List<MemberStatusRow> members, List<UUID> done,
                                                            List<UUID> started) {
        Set<UUID> doneSet = Set.copyOf(done);
        return members.stream()
                .map(m -> new RoomState.CompletionEntry(m.participantId(), m.nickname(),
                        doneSet.contains(m.participantId()), completionStatus(doneSet.contains(m.participantId()),
                            started.contains(m.participantId())), m.profileImageUrl()))
                .toList();
    }

    private static RoomState.CompletionStatus completionStatus(boolean complete, boolean started) {
        if (complete) {
            return RoomState.CompletionStatus.COMPLETED;
        }
        return started ? RoomState.CompletionStatus.IN_PROGRESS : RoomState.CompletionStatus.NOT_STARTED;
    }

    private static RoomState.Registration registration(VenueStateReader.Registration r, List<MemberStatusRow> members,
                                                       MemberStatusRow me, int capacity) {
        if (r == null) {
            return null;
        }
        return new RoomState.Registration(r.round(), r.closesAt(), r.closedAt(),
                new RoomState.Progress(r.completedParticipantIds().size(), capacity),
                completion(members, r.completedParticipantIds(), r.startedParticipantIds()), r.categories(), r.uniqueCandidateCount(),
                r.completedParticipantIds().contains(me.participantId()), r.myNominations(), r.categoryCounts(), r.myCandidates());
    }

    private static RoomState.Voting voting(VenueStateReader.Voting v, List<MemberStatusRow> members, int capacity) {
        if (v == null) {
            return null;
        }
        return new RoomState.Voting(v.round(), v.closesAt(),
                new RoomState.Progress(v.completedParticipantIds().size(), capacity),
                completion(members, v.completedParticipantIds(), v.startedParticipantIds()), v.candidates(), v.myDraft());
    }

    private static RoomState.VoteReview voteReview(VenueStateReader.VoteReview v, List<MemberStatusRow> members,
                                                   int capacity) {
        if (v == null) {
            return null;
        }
        return new RoomState.VoteReview(v.round(),
                new RoomState.Progress(v.completedParticipantIds().size(), capacity),
                completion(members, v.completedParticipantIds(), v.startedParticipantIds()), v.canExtend(), v.canProceed());
    }

    private static RoomState.TieDecision tieDecision(VenueStateReader.TieDecision t) {
        if (t == null) {
            return null;
        }
        return new RoomState.TieDecision(t.round(), t.resultRevision(),
                t.groups().stream().map(g -> new RoomState.TieGroup(g.category(), g.candidates())).toList());
    }

    private static RoomState.FinalResult finalResult(VenueStateReader.FinalResult f, SelectedMeeting meeting) {
        if (f == null) {
            return null;
        }
        return new RoomState.FinalResult(f.round(), f.resultRevision(), meeting, f.submittedCount(),
                f.selected(), f.details(), f.visitOrder(), f.confirmed());
    }
}
