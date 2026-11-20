package com.likelion4thon.Meetdle.venue;

import com.likelion4thon.Meetdle.common.logging.FeatureLog;
import com.likelion4thon.Meetdle.common.logging.FeatureLog.Event;
import com.likelion4thon.Meetdle.common.logging.FeatureLog.CloseReason;
import io.swagger.v3.oas.annotations.media.Schema;
import com.likelion4thon.Meetdle.contract.MeetingWindow;
import com.likelion4thon.Meetdle.contract.RoomVenueAccess;
import com.likelion4thon.Meetdle.contract.RoomVenueAccess.RoomVenueSnapshot;
import com.likelion4thon.Meetdle.contract.RoomVenueAccess.SystemActor;
import com.likelion4thon.Meetdle.contract.RoomPhase;
import com.likelion4thon.Meetdle.contract.VoteClosed;
import com.likelion4thon.Meetdle.contract.RoomVenueAccess.UserActor;
import com.likelion4thon.Meetdle.contract.VenueStateReader.*;
import com.likelion4thon.Meetdle.venue.RegistrationStore.Round;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static com.likelion4thon.Meetdle.contract.RoomPhase.*;
import static com.likelion4thon.Meetdle.venue.PlaceRegistration.*;
import static com.likelion4thon.Meetdle.venue.RegistrationStore.utc;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
class PlaceVoting {
    private final PlaceRegistration registration;
    private final RegistrationStore store;

    private final VoteResults results;
    private final ApplicationEventPublisher events;

    PlaceVoting(PlaceRegistration registration, RegistrationStore store, VoteResults results,
                ApplicationEventPublisher events) {
        this.registration = registration;
        this.store = store;
        this.results = results;
        this.events = events;
    }

    record TieChoice(
            @Schema(description = "방 상태의 tieDecision에 표시된 동점 카테고리")
            Category category,
            @Schema(description = "해당 카테고리의 동점 후보 중 최종 방문할 장소 UUID")
            UUID candidateId
    ) {}
    record ResolveTies(
            @Schema(description = "방 상태의 tieDecision.round", example = "1")
            int round,
            @Schema(description = "방 상태의 tieDecision.resultRevision")
            long resultRevision,
            @Schema(description = "동점 카테고리마다 최종 선택한 후보 한 개씩을 담은 목록")
            List<TieChoice> choices
    ) {}
    record ReorderVisit(
            @Schema(description = "방 상태의 result.round", example = "1")
            int round,
            @Schema(description = "방 상태의 result.resultRevision")
            long resultRevision,
            @Schema(description = "확정된 모든 장소의 candidateId를 방문 순서대로 나열한 목록")
            List<UUID> candidateIds
    ) {}

    void resolveTies(UUID roomId, UUID userId, ResolveTies request) {
        var access = registration.access();
        var lock = access.lockForWrite(roomId, new UserActor(userId));
        var snapshot = lock.snapshot();
        host(snapshot);
        var round = resultRound(snapshot, request.round(), WAITING_FOR_HOST_DECISION);
        if (!snapshot.now().isBefore(meeting(snapshot).meetingAt())) {
            throw conflict("DEADLINE_PASSED");
        }
        results.resolve(round, request.resultRevision(), request.choices(), snapshot.now());
        access.recordChange(lock, RESULT_READY);
        FeatureLog.committed(Event.TIES_RESOLVED, roomId, Map.of("round", round.number(),
                "choiceCount", request.choices().size(), "resultRevision", request.resultRevision()));
    }

    void reorderVisit(UUID roomId, UUID userId, ReorderVisit request) {
        var access = registration.access();
        var lock = access.lockForWrite(roomId, new UserActor(userId));
        var snapshot = lock.snapshot();
        host(snapshot);
        if (snapshot.phase() != RESULT_READY && snapshot.phase() != FINAL_PLACE_CONFIRMED
                && snapshot.phase() != WAITING_FOR_HOST_DECISION) {
            throw conflict("WRONG_PHASE");
        }
        var round = resultRound(snapshot, request.round(), snapshot.phase());
        if (snapshot.phase() != FINAL_PLACE_CONFIRMED && !snapshot.now().isBefore(meeting(snapshot).meetingAt())) {
            throw conflict("DEADLINE_PASSED");
        }
        if (results.reorder(round, request.resultRevision(), request.candidateIds())) {
            access.recordChange(lock, snapshot.phase());
            FeatureLog.committed(Event.VISIT_ORDER_CHANGED, roomId, Map.of("round", round.number(),
                    "placeCount", request.candidateIds().size(), "resultRevision", request.resultRevision()));
        }
    }

    record ConfirmResult(@com.fasterxml.jackson.annotation.JsonProperty(required = true) int round,
                         @com.fasterxml.jackson.annotation.JsonProperty(required = true) long resultRevision) {}

    void confirmResult(UUID roomId, UUID userId, ConfirmResult request) {
        var access = registration.access();
        var lock = access.lockForWrite(roomId, new UserActor(userId));
        var snapshot = lock.snapshot();
        host(snapshot);
        if (snapshot.phase() != RESULT_READY && snapshot.phase() != FINAL_PLACE_CONFIRMED) {
            throw conflict("WRONG_PHASE");
        }
        var round = resultRound(snapshot, request.round(), snapshot.phase());
        if (snapshot.phase() == RESULT_READY && !snapshot.now().isBefore(meeting(snapshot).meetingAt())) {
            throw conflict("DEADLINE_PASSED");
        }
        results.confirm(round, request.resultRevision(), snapshot.now());
        if (snapshot.phase() != FINAL_PLACE_CONFIRMED) {
            access.recordChange(lock, FINAL_PLACE_CONFIRMED);
            FeatureLog.committed(Event.RESULT_CONFIRMED, roomId,
                    Map.of("round", round.number(), "resultRevision", request.resultRevision()));
        }
    }

    private Round resultRound(RoomVenueSnapshot snapshot, int round, RoomPhase expectedPhase) {
        if (snapshot.phase() != expectedPhase) {
            throw conflict("WRONG_PHASE");
        }
        return store.latest(snapshot.roomId()).filter(current -> current.number() == round)
                .orElseThrow(() -> conflict("STALE_RESULT"));
    }

    void start(UUID room, UUID user, UUID key, Start request) {
        var access = registration.access();
        var lock = access.lockForWrite(room, new UserActor(user));
        var snapshot = lock.snapshot();
        host(snapshot);
        var command = registration.commandKey(room, user, key, "/voting", request);
        registration.deduplicate(snapshot, command, 204, Boolean.class, () -> {
            Round round = registration.current(snapshot, request.round());
            if (snapshot.phase() != PLACE_SUBMISSION_CLOSED) {
                throw conflict("WRONG_PHASE");
            }
            if (!store.hasRequiredCandidates(round, store.counts(round))) {
                throw conflict("REQUIRED_CATEGORY_EMPTY");
            }
            var now = snapshot.now();
            var window = MeetingWindow.calculate(meeting(snapshot).meetingAt(), now);
            Instant earliest = now.plus(window.votingMinimum());
            if (earliest.isAfter(window.votingCutoff())) {
                throw conflict("INSUFFICIENT_TIME");
            }
            if (request.closesAt() == null || request.closesAt().isBefore(earliest)
                    || request.closesAt().isAfter(window.votingCutoff())) {
                throw new VenueException(400, "INVALID_DEADLINE");
            }
            store.jdbc().update("""
                    INSERT INTO ballot_candidate (room_id, round_no, candidate_id, mode)
                    SELECT c.room_id, c.round_no, c.candidate_id,
                        CASE WHEN counts.total = 1 THEN 'AUTO_SELECTED' ELSE 'VOTE' END
                    FROM place_candidate c
                    JOIN round_category r ON r.room_id = c.room_id AND r.round_no = c.round_no AND r.category = c.category
                    JOIN (
                        SELECT category, COUNT(*) AS total FROM place_candidate
                        WHERE room_id = ? AND round_no = ? GROUP BY category
                    ) counts ON counts.category = c.category
                    WHERE c.room_id = ? AND c.round_no = ? AND r.available = TRUE
                    """, round.room(), round.number(), round.room(), round.number());
            store.jdbc().update("""
                    UPDATE venue_round SET vote_closes_at = ? WHERE room_id = ? AND round_no = ?
                    """, utc(request.closesAt()), round.room(), round.number());
            access.recordChange(lock, VOTING_OPEN);
            FeatureLog.committed(Event.VOTING_OPENED, room, Map.of("round", round.number()));
            return true;
        });
    }

    Draft save(UUID room, UUID user, Edit request) {
        var access = registration.access();
        var lock = access.lockForWrite(room, new UserActor(user));
        var snapshot = lock.snapshot();
        Round round = registration.current(snapshot, request.round());
        UUID participant = registration.participant(snapshot, round);
        requireOpen(snapshot, round);
        Draft current = draft(round, participant);
        if (current.submitted()) {
            throw conflict("ALREADY_SUBMITTED");
        }
        checkRevision(current, request.draftRevision());
        if (request.selections() == null) {
            throw new VenueException(400, "INVALID_REQUEST");
        }
        Set<UUID> eligible = competitiveIds(round);
        var seen = new HashSet<UUID>();
        for (Selection selection : request.selections()) {
            if (selection == null || selection.choice() == null || !eligible.contains(selection.candidateId())) {
                throw new VenueException(400, "INVALID_CANDIDATE");
            }
            if (!seen.add(selection.candidateId())) {
                throw new VenueException(400, "DUPLICATE_CANDIDATE");
            }
        }
        store.jdbc().update("""
                DELETE FROM vote_preference WHERE room_id = ? AND round_no = ? AND participant_id = ?
                """, round.room(), round.number(), participant.toString());
        for (Selection selection : request.selections()) {
            store.jdbc().update("""
                    INSERT INTO vote_preference (room_id, round_no, participant_id, candidate_id, choice)
                    VALUES (?, ?, ?, ?, ?)
                    """, round.room(), round.number(), participant.toString(),
                    selection.candidateId().toString(), selection.choice().name());
        }
        store.jdbc().update("""
                UPDATE round_member SET draft_revision = draft_revision + 1
                WHERE room_id = ? AND round_no = ? AND participant_id = ?
                """, round.room(), round.number(), participant.toString());
        access.recordChange(lock, VOTING_OPEN);
        FeatureLog.committed(Event.VOTE_SAVED, room, Map.of("round", round.number(),
                "participantId", participant, "selectionCount", request.selections().size(), "draftRevision", current.draftRevision() + 1));
        return draft(round, participant);
    }

    void submit(UUID room, UUID user, Submit request) {
        var access = registration.access();
        var lock = access.lockForWrite(room, new UserActor(user));
        var snapshot = lock.snapshot();
        Round round = registration.current(snapshot, request.round());
        UUID participant = registration.participant(snapshot, round);
        Draft current = draft(round, participant);
        if (current.submitted()) {
            checkRevision(current, request.draftRevision());
            return;
        }
        requireOpen(snapshot, round);
        checkRevision(current, request.draftRevision());
        var selected = current.selections().stream().map(Selection::candidateId).collect(Collectors.toSet());
        if (!selected.equals(competitiveIds(round))) {
            throw conflict("VOTE_INCOMPLETE");
        }
        store.jdbc().update("""
                UPDATE round_member SET submitted_at = ? WHERE room_id = ? AND round_no = ? AND participant_id = ?
                """, utc(snapshot.now()), round.room(), round.number(), participant.toString());
        int submittedCount = submitted(round).size();
        FeatureLog.committed(Event.VOTE_SUBMITTED, room, Map.of("round", round.number(),
                "participantId", participant, "submittedCount", submittedCount, "capacity", snapshot.capacity()));
        if (submittedCount == snapshot.capacity()) {
            tally(access, lock, round, "ALL_SUBMITTED");
        } else {
            access.recordChange(lock, VOTING_OPEN);
        }
    }

    void decide(UUID room, UUID user, UUID key, Decision request) {
        if (request.action() == null || (request.action() == DecisionAction.EXTEND && key == null)) {
            throw new VenueException(400, "INVALID_REQUEST");
        }
        var access = registration.access();
        var lock = access.lockForWrite(room, new UserActor(user));
        var snapshot = lock.snapshot();
        host(snapshot);
        if (key == null) {
            applyDecision(access, lock, request);
            return;
        }
        var command = registration.commandKey(room, user, key, "/voting/decision", request);
        registration.deduplicate(snapshot, command, 204, Boolean.class, () -> {
            applyDecision(access, lock, request);
            return true;
        });
    }

    private void applyDecision(RoomVenueAccess access, RoomVenueAccess.LockedRoomVenue lock, Decision request) {
        var snapshot = lock.snapshot();
        var round = registration.current(snapshot, request.round());
        if (request.action() == DecisionAction.EXTEND && round.voteExtensionCount() > 0) {
            throw conflict("EXTENSION_EXHAUSTED");
        }
        boolean earlyProceed = request.action() == DecisionAction.PROCEED && snapshot.phase() == VOTING_OPEN;
        if (snapshot.phase() != VOTE_REVIEW_REQUIRED && !earlyProceed) {
            throw conflict("WRONG_PHASE");
        }
        var now = snapshot.now();
        var cutoff = MeetingWindow.calculate(meeting(snapshot).meetingAt(), now).votingCutoff();
        if (!now.isBefore(cutoff)) {
            throw conflict("INSUFFICIENT_TIME");
        }
        if (request.action() == DecisionAction.PROCEED) {
            if (request.closesAt() != null) {
                throw new VenueException(400, "INVALID_DEADLINE");
            }
            if (submitted(round).isEmpty()) {
                throw conflict("NO_SUBMITTED_VOTES");
            }
            tally(access, lock, round, "HOST_PROCEED");
            return;
        }
        var deadline = request.closesAt();
        if (deadline == null || !deadline.isAfter(now) || !deadline.isAfter(round.voteClosesAt())
                || deadline.isAfter(cutoff)) {
            throw new VenueException(400, "INVALID_DEADLINE");
        }
        store.jdbc().update("""
                UPDATE venue_round SET vote_closes_at = ?, vote_extension_count = 1, vote_review_started_at = NULL
                WHERE room_id = ? AND round_no = ?
                """, utc(deadline), round.room(), round.number());
        access.recordChange(lock, VOTING_OPEN);
        FeatureLog.committed(Event.VOTING_EXTENDED, snapshot.roomId(), Map.of("round", round.number(), "extensionCount", 1));
    }

    PlaceRegistration.Extended recollect(UUID room, UUID user, UUID key, Recollect request) {
        var access = registration.access();
        var lock = access.lockForWrite(room, new UserActor(user));
        var snapshot = lock.snapshot();
        host(snapshot);
        var command = registration.commandKey(room, user, key, "/recovery", request);
        return registration.deduplicate(snapshot, command, 201, PlaceRegistration.Extended.class, () -> {
            var round = registration.current(snapshot, request.round());
            if (RecoveryReason.RETRY_EXHAUSTED.name().equals(round.recoveryReason())) {
                throw conflict("RETRY_EXHAUSTED");
            }
            if (RecoveryReason.INSUFFICIENT_TIME.name().equals(round.recoveryReason())) {
                throw conflict("INSUFFICIENT_TIME");
            }
            if (snapshot.phase() != RECOVERY_REQUIRED
                    || !RecoveryReason.NO_ELIGIBLE_RESULT.name().equals(round.recoveryReason())) {
                throw conflict("WRONG_PHASE");
            }
            var meeting = meeting(snapshot);
            var deadline = registrationDeadline(snapshot, meeting, request.closesAt(), true);
            var opened = registration.createRound(access, lock, meeting, deadline, store.categories(round));
            return new PlaceRegistration.Extended(opened.round(), opened.closesAt());
        });
    }

    void advanceDeadline(UUID room) {
        var access = registration.access();
        var lock = access.lockForWrite(room, SystemActor.DEADLINE_SCHEDULER);
        var snapshot = lock.snapshot();
        var round = store.latest(room).orElse(null);
        if (round == null) {
            return;
        }
        var now = snapshot.now();
        if (snapshot.phase() == WAITING_FOR_HOST_DECISION || snapshot.phase() == RESULT_READY) {
            if (!now.isBefore(meeting(snapshot).meetingAt())) {
                setRecovery(round, snapshot.phase() == RESULT_READY ? RecoveryReason.CONFIRMATION_EXPIRED
                        : RecoveryReason.HOST_DECISION_EXPIRED);
                access.recordChange(lock, ENDED);
            }
            return;
        }
        if (snapshot.phase() == RECOVERY_REQUIRED) {
            if (!canRecollect(meeting(snapshot).meetingAt(), now)) {
                setRecovery(round, RecoveryReason.INSUFFICIENT_TIME);
                access.recordChange(lock, ENDED);
            }
            return;
        }
        if (snapshot.phase() != VOTING_OPEN && snapshot.phase() != VOTE_REVIEW_REQUIRED) {
            return;
        }
        if (round.voteClosesAt() == null || now.isBefore(round.voteClosesAt())) {
            return;
        }
        var cutoff = MeetingWindow.calculate(meeting(snapshot).meetingAt(), now).votingCutoff();
        boolean noVotes = submitted(round).isEmpty();
        if (!now.isBefore(cutoff) || (noVotes && round.voteExtensionCount() > 0)) {
            close(access, lock, round, ENDED, noVotes ? "NO_SUBMITTED_VOTES" : "DECISION_EXPIRED");
        } else if (snapshot.phase() == VOTING_OPEN) {
            store.jdbc().update("""
                    UPDATE venue_round SET vote_review_started_at = ? WHERE room_id = ? AND round_no = ?
                    """, utc(now), round.room(), round.number());
            access.recordChange(lock, VOTE_REVIEW_REQUIRED);
        }
    }

    private void tally(RoomVenueAccess access, RoomVenueAccess.LockedRoomVenue lock, Round round, String reason) {
        var snapshot = lock.snapshot();
        var next = results.compute(round, submitted(round).size(), snapshot.now());
        if (next == RECOVERY_REQUIRED) {
            var recovery = recoveryReason(round, meeting(snapshot).meetingAt(), snapshot.now());
            setRecovery(round, recovery);
            if (recovery != RecoveryReason.NO_ELIGIBLE_RESULT) {
                next = ENDED;
            }
        }
        close(access, lock, round, next, reason);
    }

    private void close(RoomVenueAccess access, RoomVenueAccess.LockedRoomVenue lock, Round round,
                       RoomPhase next, String reason) {
        var now = lock.snapshot().now();
        // The room lock serializes closure. The persisted round identifies the event after commit.
        store.jdbc().update("""
                UPDATE venue_round SET vote_closed_at = ?, vote_close_reason = ?
                WHERE room_id = ? AND round_no = ?
                """, utc(now), reason, round.room(), round.number());
        if (next == ENDED && List.of("NO_SUBMITTED_VOTES", "DECISION_EXPIRED").contains(reason)) {
            setRecovery(round, RecoveryReason.valueOf(reason));
        }
        access.recordChange(lock, next);
        FeatureLog.committed(Event.VOTING_CLOSED, lock.snapshot().roomId(), Map.of("round", round.number(),
                "reason", CloseReason.of(reason), "submittedCount", submitted(round).size(), "nextPhase", next));
        events.publishEvent(new VoteClosed(lock.snapshot().roomId(), round.number(), now));
    }

    private void setRecovery(Round round, RecoveryReason reason) {
        store.jdbc().update("""
                UPDATE venue_round SET recovery_reason = ? WHERE room_id = ? AND round_no = ?
                """, reason.name(), round.room(), round.number());
    }

    static RecoveryReason recoveryReason(Round round, Instant meetingAt, Instant now) {
        if (round.attempt() > 1) {
            return RecoveryReason.RETRY_EXHAUSTED;
        }
        return canRecollect(meetingAt, now) ? RecoveryReason.NO_ELIGIBLE_RESULT : RecoveryReason.INSUFFICIENT_TIME;
    }

    private static boolean canRecollect(Instant meetingAt, Instant now) {
        var window = MeetingWindow.calculate(meetingAt, now);
        return !now.plus(window.registrationMinimum()).isAfter(window.registrationCutoff());
    }

    List<UUID> submitted(Round round) {
        return store.jdbc().query("""
                SELECT participant_id FROM round_member
                WHERE room_id = ? AND round_no = ? AND submitted_at IS NOT NULL ORDER BY participant_id
                """, (rs, row) -> UUID.fromString(rs.getString(1)), round.room(), round.number());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Voting project(Round round, UUID participant) {
        var completed = submitted(round);
        var candidates = store.jdbc().query("""
                SELECT c.*, b.mode FROM ballot_candidate b JOIN place_candidate c
                  ON c.room_id = b.room_id AND c.round_no = b.round_no AND c.candidate_id = b.candidate_id
                WHERE b.room_id = ? AND b.round_no = ? ORDER BY c.category, c.candidate_id
                """, (rs, row) -> new BallotCandidate(UUID.fromString(rs.getString("candidate_id")),
                RegistrationStore.place(rs), BallotMode.valueOf(rs.getString("mode"))), round.room(), round.number());
        Draft own = store.eligible(round, participant) ? draft(round, participant) : null;
        return new Voting(round.number(), round.voteClosesAt(), completed, candidates, own, store.started(round, true));
    }

    private Draft draft(Round round, UUID participant) {
        var selections = store.jdbc().query("""
                SELECT candidate_id, choice FROM vote_preference
                WHERE room_id = ? AND round_no = ? AND participant_id = ? ORDER BY candidate_id
                """, (rs, row) -> new Selection(UUID.fromString(rs.getString(1)), Choice.valueOf(rs.getString(2))),
                round.room(), round.number(), participant.toString());
        return store.jdbc().queryForObject("""
                SELECT draft_revision, submitted_at FROM round_member
                WHERE room_id = ? AND round_no = ? AND participant_id = ?
                """, (rs, row) -> new Draft(rs.getLong(1), selections, rs.getObject(2) != null),
                round.room(), round.number(), participant.toString());
    }

    private Set<UUID> competitiveIds(Round round) {
        return new HashSet<>(store.jdbc().query("""
                SELECT candidate_id FROM ballot_candidate WHERE room_id = ? AND round_no = ? AND mode = 'VOTE'
                """, (rs, row) -> UUID.fromString(rs.getString(1)), round.room(), round.number()));
    }

    private static void requireOpen(RoomVenueSnapshot snapshot, Round round) {
        if (snapshot.phase() != VOTING_OPEN || round.voteClosesAt() == null) {
            throw conflict("WRONG_PHASE");
        }
        if (!snapshot.now().isBefore(round.voteClosesAt())) {
            throw conflict("DEADLINE_PASSED");
        }
    }

    private static void checkRevision(Draft draft, Long revision) {
        if (revision == null || revision < 0) {
            throw new VenueException(400, "INVALID_REQUEST");
        }
        if (draft.draftRevision() != revision) {
            throw conflict("STALE_DRAFT");
        }
    }

    enum DecisionAction { EXTEND, PROCEED }

    record Decision(
            @Schema(description = "방 상태의 voting.round 또는 voteReview.round", example = "1")
            int round,
            @Schema(description = "EXTEND는 마감 후 투표 시간 연장, PROCEED는 마감 전후 제출자 1명 이상일 때 현재 제출표 집계")
            DecisionAction action,
            @Schema(description = "EXTEND의 새 투표 마감 시각. ISO 8601 형식. PROCEED에서는 생략")
            Instant closesAt
    ) {}
    record Recollect(
            @Schema(description = "방 상태의 recovery.round", example = "1")
            int round,
            @Schema(description = "새 등록 라운드의 마감 시각. ISO 8601 형식의 필수 값")
            Instant closesAt
    ) {}
    record Start(
            @Schema(description = "종료된 장소 등록 라운드. 방 상태의 registration.round 사용", example = "1")
            int round,
            @Schema(description = "투표 마감 시각. ISO 8601 형식의 필수 값")
            Instant closesAt
    ) {}
    record Edit(
            @Schema(description = "방 상태의 voting.round", example = "1")
            int round,
            @Schema(description = "방 상태의 voting.myDraft.draftRevision 또는 직전 임시 저장 응답의 버전. 최초 초안은 0", example = "0")
            Long draftRevision,
            @Schema(description = "이번에 저장할 전체 선호도 목록. mode가 VOTE인 후보만 후보당 한 번 지정")
            List<Selection> selections
    ) {}
    record Submit(
            @Schema(description = "방 상태의 voting.round", example = "1")
            int round,
            @Schema(description = "직전 선호도 임시 저장 응답의 draftRevision. 현재 초안과 같은 값 사용", example = "1")
            Long draftRevision
    ) {}
}
