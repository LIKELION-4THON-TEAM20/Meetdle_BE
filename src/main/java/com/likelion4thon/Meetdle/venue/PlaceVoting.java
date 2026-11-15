package com.likelion4thon.Meetdle.venue;

import com.likelion4thon.Meetdle.contract.MeetingWindow;
import com.likelion4thon.Meetdle.contract.RoomVenueAccess.RoomVenueSnapshot;
import com.likelion4thon.Meetdle.contract.RoomVenueAccess.UserActor;
import com.likelion4thon.Meetdle.contract.VenueStateReader.*;
import com.likelion4thon.Meetdle.venue.RegistrationStore.Round;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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

    PlaceVoting(PlaceRegistration registration, RegistrationStore store) {
        this.registration = registration;
        this.store = store;
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
                        CASE WHEN r.required = TRUE AND counts.total = 1 THEN 'AUTO_SELECTED' ELSE 'VOTE' END
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
        access.recordChange(lock, VOTING_OPEN);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Voting project(Round round, UUID participant) {
        var completed = store.jdbc().query("""
                SELECT participant_id FROM round_member
                WHERE room_id = ? AND round_no = ? AND submitted_at IS NOT NULL ORDER BY participant_id
                """, (rs, row) -> UUID.fromString(rs.getString(1)), round.room(), round.number());
        var candidates = store.jdbc().query("""
                SELECT c.*, b.mode FROM ballot_candidate b JOIN place_candidate c
                  ON c.room_id = b.room_id AND c.round_no = b.round_no AND c.candidate_id = b.candidate_id
                WHERE b.room_id = ? AND b.round_no = ? ORDER BY c.category, c.candidate_id
                """, (rs, row) -> new BallotCandidate(UUID.fromString(rs.getString("candidate_id")),
                RegistrationStore.place(rs), BallotMode.valueOf(rs.getString("mode"))), round.room(), round.number());
        Draft own = store.eligible(round, participant) ? draft(round, participant) : null;
        return new Voting(round.number(), round.voteClosesAt(), completed, candidates, own);
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

    record Start(int round, Instant closesAt) {}
    record Edit(int round, Long draftRevision, List<Selection> selections) {}
    record Submit(int round, Long draftRevision) {}
}
