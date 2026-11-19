package com.likelion4thon.Meetdle.venue;

import com.likelion4thon.Meetdle.contract.RoomMember;
import com.likelion4thon.Meetdle.contract.RoomPhase;
import com.likelion4thon.Meetdle.contract.RoomVenueAccess;
import com.likelion4thon.Meetdle.contract.VoteClosed;
import com.likelion4thon.Meetdle.contract.VenueStateReader;
import com.likelion4thon.Meetdle.contract.VenueStateReader.Selection;
import com.likelion4thon.Meetdle.contract.VenueStateReader.Category;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import static com.likelion4thon.Meetdle.contract.BoundaryFixtures.*;
import static com.likelion4thon.Meetdle.contract.RoomPhase.*;
import static com.likelion4thon.Meetdle.contract.VenueStateReader.Choice.DISLIKE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:vote-recovery;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Sql("/fixtures/base-room.sql")
@RecordApplicationEvents
@Transactional
class VoteRecoveryTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlaceVoting voting;
    @Autowired PlaceRegistration registration;
    @Autowired RegistrationStore store;
    @Autowired PickTokens picks;
    @Autowired RegistrationDeadlines deadlines;
    @Autowired VenueStateReader reader;
    @Autowired ApplicationEvents events;
    @MockitoBean RoomVenueAccess rooms;

    @Test
    void hostCanCloseEarlyWithOneSubmittedVoteAndUnsubmittedDraftsAreExcluded() {
        var candidates = prepareVoting();
        var host = confirmedVenueMember(HOST.userId()).orElseThrow();
        var memberId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var member = confirmedVenueMember(memberId).orElseThrow();
        var proceed = new PlaceVoting.Decision(1, PlaceVoting.DecisionAction.PROCEED, null);
        assertEquals(false, project(host).voteReview().canProceed());
        assertEquals("NO_SUBMITTED_VOTES", assertThrows(VenueException.class,
                () -> voting.decide(ROOM_ID, HOST.userId(), null, proceed)).getMessage());
        assertEquals("FORBIDDEN", assertThrows(VenueException.class,
                () -> voting.decide(ROOM_ID, memberId, null, proceed)).getMessage());
        assertEquals("WRONG_PHASE", assertThrows(VenueException.class, () -> voting.decide(ROOM_ID,
                HOST.userId(), UUID.randomUUID(), new PlaceVoting.Decision(1, PlaceVoting.DecisionAction.EXTEND,
                        NOW.plusSeconds(7200)))).getMessage());
        voting.save(ROOM_ID, HOST.userId(), new PlaceVoting.Edit(1, 0L, List.of(
                new Selection(candidates.get(0), VenueStateReader.Choice.LIKE),
                new Selection(candidates.get(1), VenueStateReader.Choice.NEUTRAL))));
        voting.submit(ROOM_ID, HOST.userId(), new PlaceVoting.Submit(1, 1L));
        voting.save(ROOM_ID, memberId, new PlaceVoting.Edit(1, 0L, List.of(
                new Selection(candidates.get(0), DISLIKE),
                new Selection(candidates.get(1), VenueStateReader.Choice.LIKE))));
        var deadline = store.latest(ROOM_ID).orElseThrow().voteClosesAt();
        assertEquals(VOTING_OPEN, phase());
        assertEquals(true, project(host).voteReview().canProceed());
        assertEquals(false, project(host).voteReview().canExtend());
        assertEquals(false, project(member).voteReview().canProceed());
        assertEquals(deadline, store.latest(ROOM_ID).orElseThrow().voteClosesAt());
        assertEquals("STALE_ROUND", assertThrows(VenueException.class, () -> voting.decide(ROOM_ID,
                HOST.userId(), null, new PlaceVoting.Decision(2, PlaceVoting.DecisionAction.PROCEED, null))).getMessage());
        assertEquals("INVALID_DEADLINE", assertThrows(VenueException.class, () -> voting.decide(ROOM_ID,
                HOST.userId(), null, new PlaceVoting.Decision(1, PlaceVoting.DecisionAction.PROCEED, deadline))).getMessage());

        var key = UUID.randomUUID();
        voting.decide(ROOM_ID, HOST.userId(), key, proceed);
        voting.decide(ROOM_ID, HOST.userId(), key, proceed);
        assertEquals(RESULT_READY, phase());
        assertEquals(1, project(host).result().submittedCount());
        assertEquals(new VenueStateReader.Aggregate(1, 0, 0), project(host).result().selected().get(0).aggregate());
        assertNull(project(member).result());
        assertEquals(List.of(HOST_PARTICIPANT), project(member).voting().completedParticipantIds());
        assertEquals(true, project(member).voting().startedParticipantIds().contains(member.participantId()));
        assertNull(project(member).voting().myDraft());
        assertEquals(List.of(), project(member).voting().candidates());
        assertEquals(deadline, store.latest(ROOM_ID).orElseThrow().voteClosesAt());
        assertEquals(1, events.stream(VoteClosed.class).count());
        assertEquals("WRONG_PHASE", assertThrows(VenueException.class,
                () -> voting.submit(ROOM_ID, memberId, new PlaceVoting.Submit(1, 1L))).getMessage());
    }

    @Test
    void allSubmittedKeepsCompletionAndHidesResultsUntilHostConfirmation() {
        var candidates = prepareVoting();
        for (UUID user : List.of(HOST.userId(), UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000003"))) {
            voting.save(ROOM_ID, user, new PlaceVoting.Edit(1, 0L, List.of(
                    new Selection(candidates.get(0), VenueStateReader.Choice.LIKE),
                    new Selection(candidates.get(1), VenueStateReader.Choice.LIKE))));
            voting.submit(ROOM_ID, user, new PlaceVoting.Submit(1, 1L));
        }
        var host = confirmedVenueMember(HOST.userId()).orElseThrow();
        var member = confirmedVenueMember(UUID.fromString("00000000-0000-0000-0000-000000000002")).orElseThrow();
        assertEquals(WAITING_FOR_HOST_DECISION, phase());
        assertEquals(3, project(member).voting().completedParticipantIds().size());
        assertNull(project(member).result());
        assertNull(project(member).tieDecision());
        assertEquals(1, events.stream(VoteClosed.class).count());
        voting.resolveTies(ROOM_ID, HOST.userId(), new PlaceVoting.ResolveTies(1,
                project(host).tieDecision().resultRevision(),
                List.of(new PlaceVoting.TieChoice(Category.FOOD, candidates.get(0)))));
        assertEquals(RESULT_READY, phase());
        assertEquals(3, project(member).voting().completedParticipantIds().size());
        assertNull(project(member).result());
        voting.confirmResult(ROOM_ID, HOST.userId(),
                new PlaceVoting.ConfirmResult(1, project(host).result().resultRevision()));
        assertEquals(FINAL_PLACE_CONFIRMED, phase());
        assertEquals(true, project(member).result().confirmed());
        assertEquals(List.of(candidates.get(0)), project(member).result().visitOrder());
        assertEquals(3, project(member).voting().completedParticipantIds().size());
    }

    private List<UUID> prepareVoting() {
        prepareRegistration();
        jdbc.update("UPDATE round_category SET required = FALSE WHERE category = 'CAFE'");
        var first = registration.addNomination(ROOM_ID, HOST.userId(), Category.FOOD,
                new PlaceRegistration.Nomination(1, pick("1", Category.FOOD)));
        var second = registration.addNomination(ROOM_ID, HOST.userId(), Category.FOOD,
                new PlaceRegistration.Nomination(1, pick("2", Category.FOOD)));
        jdbc.update("UPDATE room SET phase = 'PLACE_SUBMISSION_CLOSED' WHERE room_id = ?", ROOM_ID.toString());
        voting.start(ROOM_ID, HOST.userId(), UUID.randomUUID(), new PlaceVoting.Start(1, NOW.plusSeconds(3600)));
        return List.of(first, second);
    }

    @Test
    void severalPlacesInOneCategoryCanCompleteAndMissingRoomCategoryCanBeReopened() {
        prepareRegistration();
        var round = store.latest(ROOM_ID).orElseThrow();
        var host = HOST.userId();
        var member = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var third = UUID.fromString("00000000-0000-0000-0000-000000000003");
        var first = new PlaceRegistration.Nomination(1, pick("1", Category.FOOD));
        var second = new PlaceRegistration.Nomination(1, pick("2", Category.FOOD));
        var firstId = registration.addNomination(ROOM_ID, host, Category.FOOD, first);
        var secondId = registration.addNomination(ROOM_ID, host, Category.FOOD, second);
        assertEquals(secondId, registration.addNomination(ROOM_ID, host, Category.FOOD, second));
        assertEquals(2, store.myCandidates(round, HOST_PARTICIPANT).size());
        registration.removeNomination(ROOM_ID, host, 1, secondId);
        assertEquals(List.of(firstId), store.myCandidates(round, HOST_PARTICIPANT).stream().map(value -> value.candidateId()).toList());
        registration.addNomination(ROOM_ID, member, Category.FOOD, first);
        registration.addNomination(ROOM_ID, third, Category.FOOD, first);
        for (var user : List.of(host, member, third)) {
            registration.complete(ROOM_ID, user, new PlaceRegistration.Completion(1));
        }
        assertEquals(PLACE_SUBMISSION_CLOSED, phase());
        assertEquals(true, project(confirmedVenueMember(host).orElseThrow()).allowedActions()
                .contains(VenueStateReader.Action.EXTEND_COLLECTION));
        registration.extend(ROOM_ID, host, UUID.randomUUID(),
                new PlaceRegistration.Extension(1, PlaceRegistration.ExtensionMode.EXTEND, NOW.plusSeconds(7200)));
        assertEquals(PLACE_SUBMISSION_OPEN, phase());
        assertEquals(0, store.completed(store.latest(ROOM_ID).orElseThrow()).size());
        registration.addNomination(ROOM_ID, host, Category.CAFE,
                new PlaceRegistration.Nomination(1, pick("3", Category.CAFE)));
        assertEquals(true, store.hasRequiredCandidates(round, store.counts(round)));
    }

    @Test
    void singleOptionalCategoryIsSelectedAutomaticallyAndStillNeedsHostConfirmation() {
        prepareRegistration();
        jdbc.update("UPDATE round_category SET required = FALSE WHERE category = 'CAFE'");
        var request = new PlaceRegistration.Nomination(1, pick("1", Category.FOOD));
        var member = UUID.fromString("00000000-0000-0000-0000-000000000002");
        registration.addNomination(ROOM_ID, HOST.userId(), Category.FOOD, request);
        registration.addNomination(ROOM_ID, member, Category.FOOD, request);
        registration.addNomination(ROOM_ID, HOST.userId(), Category.CAFE,
                new PlaceRegistration.Nomination(1, pick("3", Category.CAFE)));
        registration.complete(ROOM_ID, HOST.userId(), new PlaceRegistration.Completion(1));
        registration.complete(ROOM_ID, member, new PlaceRegistration.Completion(1));
        registration.closeEarly(ROOM_ID, HOST.userId(), new PlaceRegistration.Completion(1));
        assertEquals(RESULT_READY, phase());
        var result = store.latest(ROOM_ID).map(round -> project(confirmedVenueMember(HOST.userId()).orElseThrow()).result()).orElseThrow();
        assertEquals(2, result.selected().size());
        assertEquals(false, result.confirmed());
        voting.confirmResult(ROOM_ID, HOST.userId(), new PlaceVoting.ConfirmResult(1, result.resultRevision()));
        assertEquals(FINAL_PLACE_CONFIRMED, phase());
    }

    private void prepareRegistration() {
        mockRoom(new AtomicReference<>(NOW));
        jdbc.update("UPDATE room SET phase = 'PLACE_SUBMISSION_OPEN', capacity = 3 WHERE room_id = ?", ROOM_ID.toString());
        jdbc.update("""
                INSERT INTO venue_round (room_id, round_no, selection_id, collection_closes_at)
                VALUES (?, 1, ?, ?)
                """, ROOM_ID.toString(), confirmedVenueRoom().selectedMeeting().orElseThrow().selectionId().toString(),
                RegistrationStore.utc(NOW.plusSeconds(3600)));
        jdbc.update("""
                INSERT INTO round_member (room_id, round_no, participant_id)
                SELECT room_id, 1, participant_id FROM room_member WHERE room_id = ?
                """, ROOM_ID.toString());
        jdbc.update("""
                INSERT INTO round_category (room_id, round_no, category, required, available, radius_meters)
                VALUES (?, 1, 'FOOD', TRUE, TRUE, 2500), (?, 1, 'CAFE', TRUE, TRUE, 2500)
                """, ROOM_ID.toString(), ROOM_ID.toString());
    }

    private String pick(String providerId, Category category) {
        var meeting = confirmedVenueRoom().selectedMeeting().orElseThrow();
        var place = new VenueStateReader.Place("Place " + providerId, "Address", meeting.station().lat(),
                meeting.station().lng(), category, null);
        return picks.issue(new PickTokens.Pick(ROOM_ID, meeting.selectionId(), 1, NOW.plusSeconds(900), providerId, place));
    }

    @Test
    void fullCapacityClosesOnceAndRecollectionStartsWithAnEmptyBallot() {
        var confirmed = confirmedVenueRoom();
        var room = ROOM_ID.toString();
        var selection = confirmed.selectedMeeting().orElseThrow().selectionId().toString();
        var candidate = UUID.randomUUID();
        var deadline = NOW.plusSeconds(3600);
        var now = new AtomicReference<>(deadline);
        jdbc.update("UPDATE room SET phase = 'VOTING_OPEN', capacity = 3 WHERE room_id = ?", room);
        jdbc.update("""
                INSERT INTO venue_round (room_id, round_no, selection_id, collection_closes_at, vote_closes_at)
                VALUES (?, 1, ?, ?, ?)
                """, room, selection, RegistrationStore.utc(NOW), RegistrationStore.utc(deadline));
        jdbc.update("""
                INSERT INTO round_member (room_id, round_no, participant_id)
                SELECT room_id, 1, participant_id FROM room_member WHERE room_id = ?
                """, room);
        jdbc.update("""
                INSERT INTO round_category (room_id, round_no, category, required, available, radius_meters)
                VALUES (?, 1, 'FOOD', TRUE, TRUE, 2500)
                """, room);
        jdbc.update("""
                INSERT INTO place_candidate (room_id, round_no, candidate_id, category, provider_place_id,
                    name, address, lat, lng)
                VALUES (?, 1, ?, 'FOOD', '1', 'Food', 'Address', 37, 127)
                """, room, candidate.toString());
        jdbc.update("""
                INSERT INTO ballot_candidate (room_id, round_no, candidate_id, mode) VALUES (?, 1, ?, 'VOTE')
                """, room, candidate.toString());

        mockRoom(now);

        voting.advanceDeadline(ROOM_ID);
        assertEquals(VOTE_REVIEW_REQUIRED, phase());
        assertEquals("NO_SUBMITTED_VOTES", assertThrows(VenueException.class, () ->
                voting.decide(ROOM_ID, HOST.userId(), null,
                        new PlaceVoting.Decision(1, PlaceVoting.DecisionAction.PROCEED, null))).getMessage());
        voting.decide(ROOM_ID, HOST.userId(), UUID.randomUUID(),
                new PlaceVoting.Decision(1, PlaceVoting.DecisionAction.EXTEND, deadline.plusSeconds(1800)));

        var lastSubmit = new PlaceVoting.Submit(1, 1L);
        var edit = new PlaceVoting.Edit(1, 0L, List.of(
                new Selection(candidate, DISLIKE)));
        var members = List.of(HOST.userId(), UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000003"));
        for (UUID user : members) {
            voting.save(ROOM_ID, user, edit);
            voting.submit(ROOM_ID, user, lastSubmit);
        }
        assertEquals(RECOVERY_REQUIRED, phase());
        voting.submit(ROOM_ID, members.get(2), lastSubmit);
        voting.advanceDeadline(ROOM_ID);
        assertEquals(List.of(new VoteClosed(ROOM_ID, 1, now.get())), events.stream(VoteClosed.class).toList());
        assertEquals("EXCLUDED", jdbc.queryForObject("SELECT result_status FROM result_place", String.class));

        var request = new PlaceVoting.Recollect(1, NOW.plusSeconds(7200));
        var key = UUID.randomUUID();
        var opened = voting.recollect(ROOM_ID, HOST.userId(), key, request);
        assertEquals(opened, voting.recollect(ROOM_ID, HOST.userId(), key, request));
        assertEquals(2, opened.round());
        assertEquals(PLACE_SUBMISSION_OPEN, phase());
        assertEquals(3, jdbc.queryForObject("""
                SELECT COUNT(*) FROM round_member
                WHERE round_no = 2 AND draft_revision = 0 AND submitted_at IS NULL AND registration_completed_at IS NULL
                """, Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM vote_preference WHERE round_no = 2", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM place_candidate WHERE round_no = 2", Integer.class));
        assertEquals("STALE_ROUND", assertThrows(VenueException.class,
                () -> voting.recollect(ROOM_ID, HOST.userId(), UUID.randomUUID(), request)).getMessage());
    }

    @Test
    void hostResolvesTiesAndReordersPublishedCourse() {
        var room = ROOM_ID.toString();
        var now = new AtomicReference<>(NOW);
        var meal = UUID.randomUUID();
        var runnerUp = UUID.randomUUID();
        var cafe = UUID.randomUUID();
        var meeting = confirmedVenueRoom().selectedMeeting().orElseThrow();
        mockRoom(now);
        jdbc.update("UPDATE room SET phase = 'WAITING_FOR_HOST_DECISION' WHERE room_id = ?", room);
        jdbc.update("""
                INSERT INTO venue_round (room_id, round_no, selection_id, collection_closes_at)
                VALUES (?, 1, ?, ?)
                """, room, meeting.selectionId().toString(), RegistrationStore.utc(NOW));
        jdbc.update("""
                INSERT INTO venue_result (room_id, round_no, result_revision, submitted_count, computed_at)
                VALUES (?, 1, 1, 1, ?)
                """, room, RegistrationStore.utc(NOW));
        jdbc.update("""
                INSERT INTO round_category (room_id, round_no, category, required, available, radius_meters)
                VALUES (?, 1, 'FOOD', TRUE, TRUE, 2500), (?, 1, 'CAFE', TRUE, TRUE, 2500)
                """, room, room);
        addResult(meal, Category.FOOD, "TIED");
        addResult(runnerUp, Category.FOOD, "TIED");
        addResult(cafe, Category.CAFE, "SELECTED");

        var host = confirmedVenueMember(HOST.userId()).orElseThrow();
        var memberId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var member = confirmedVenueMember(memberId).orElseThrow();
        assertEquals(1, project(host).tieDecision().groups().size());
        assertEquals(List.of(cafe), project(host).result().selected().stream().map(value -> value.candidateId()).toList());
        assertEquals(false, project(host).result().confirmed());
        assertNull(project(member).tieDecision());
        assertNull(project(member).result());

        var request = new PlaceVoting.ResolveTies(1, 1, List.of(new PlaceVoting.TieChoice(Category.FOOD, meal)));
        assertEquals("FORBIDDEN", assertThrows(VenueException.class,
                () -> voting.resolveTies(ROOM_ID, memberId, request)).getMessage());
        assertEquals("INVALID_TIE_CHOICE", assertThrows(VenueException.class,
                () -> voting.resolveTies(ROOM_ID, HOST.userId(), new PlaceVoting.ResolveTies(1, 1,
                        List.of(new PlaceVoting.TieChoice(Category.FOOD, cafe))))).getMessage());
        voting.resolveTies(ROOM_ID, HOST.userId(), request);
        assertEquals(RESULT_READY, phase());
        assertNull(project(member).result());
        var result = project(host).result();
        assertEquals(List.of(meal, cafe), result.visitOrder());
        assertEquals(2, result.resultRevision());

        assertEquals("INVALID_VISIT_ORDER", assertThrows(VenueException.class,
                () -> voting.reorderVisit(ROOM_ID, HOST.userId(),
                        new PlaceVoting.ReorderVisit(1, 2, List.of(cafe, cafe)))).getMessage());
        voting.reorderVisit(ROOM_ID, HOST.userId(), new PlaceVoting.ReorderVisit(1, 2, List.of(cafe, meal)));
        assertNull(project(member).result());
        assertEquals(List.of(cafe, meal), project(host).result().visitOrder());
        assertEquals(3, project(host).result().resultRevision());
        assertEquals("STALE_RESULT", assertThrows(VenueException.class,
                () -> voting.reorderVisit(ROOM_ID, HOST.userId(),
                        new PlaceVoting.ReorderVisit(1, 2, List.of(meal, cafe)))).getMessage());
        assertEquals("STALE_RESULT", assertThrows(VenueException.class, () ->
                voting.confirmResult(ROOM_ID, HOST.userId(), new PlaceVoting.ConfirmResult(1, 2))).getMessage());
        voting.confirmResult(ROOM_ID, HOST.userId(), new PlaceVoting.ConfirmResult(1, 3));
        voting.confirmResult(ROOM_ID, HOST.userId(), new PlaceVoting.ConfirmResult(1, 3));
        assertEquals(FINAL_PLACE_CONFIRMED, phase());
        assertEquals(true, project(member).result().confirmed());
    }

    @Test
    void expiresUnresolvedTiesAtMeetingStartWithoutClosingTheVoteAgain() {
        var meeting = confirmedVenueRoom().selectedMeeting().orElseThrow();
        var now = new AtomicReference<>(meeting.meetingAt().minusSeconds(1));
        var room = ROOM_ID.toString();
        mockRoom(now);
        jdbc.update("UPDATE room SET phase = 'WAITING_FOR_HOST_DECISION' WHERE room_id = ?", room);
        jdbc.update("""
                INSERT INTO venue_round (room_id, round_no, selection_id, collection_closes_at,
                    vote_closes_at, vote_closed_at)
                VALUES (?, 1, ?, ?, ?, ?)
                """, room, meeting.selectionId().toString(), RegistrationStore.utc(NOW),
                RegistrationStore.utc(NOW.plusSeconds(3600)), RegistrationStore.utc(NOW.plusSeconds(3600)));
        jdbc.update("""
                INSERT INTO venue_result (room_id, round_no, result_revision, submitted_count, computed_at)
                VALUES (?, 1, 1, 1, ?)
                """, room, RegistrationStore.utc(NOW.plusSeconds(3600)));
        var host = confirmedVenueMember(HOST.userId()).orElseThrow();
        var candidate = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO round_category (room_id, round_no, category, required, available, radius_meters)
                VALUES (?, 1, 'FOOD', TRUE, TRUE, 2500)
                """, room);
        addResult(candidate, Category.FOOD, "TIED");
        addResult(UUID.randomUUID(), Category.FOOD, "TIED");
        deadlines.advance();
        assertEquals(WAITING_FOR_HOST_DECISION, phase());
        assertEquals(List.of(VenueStateReader.Action.RESOLVE_TIES), project(host, now.get()).allowedActions());

        now.set(meeting.meetingAt());
        assertNull(project(host, now.get()).tieDecision());
        assertEquals(List.of(), project(host, now.get()).allowedActions());
        var request = new PlaceVoting.ResolveTies(1, 1,
                List.of(new PlaceVoting.TieChoice(Category.FOOD, candidate)));
        assertEquals("DEADLINE_PASSED", assertThrows(VenueException.class,
                () -> voting.resolveTies(ROOM_ID, HOST.userId(), request)).getMessage());
        deadlines.advance();
        deadlines.advance();
        assertEquals(ENDED, phase());
        assertEquals(0, events.stream(VoteClosed.class).count());
    }

    private VenueStateReader.VenueState project(RoomMember viewer) {
        return project(viewer, NOW);
    }

    private VenueStateReader.VenueState project(RoomMember viewer, Instant now) {
        return reader.project(new VenueStateReader.ViewContext(ROOM_ID, viewer, phase(), 3,
                confirmedVenueRoom().selectedMeeting().map(meeting -> meeting.meetingAt()), now));
    }

    private void addResult(UUID candidate, Category category, String status) {
        var room = ROOM_ID.toString();
        var id = candidate.toString();
        jdbc.update("""
                INSERT INTO place_candidate (room_id, round_no, candidate_id, category, provider_place_id,
                    name, address, lat, lng)
                VALUES (?, 1, ?, ?, ?, 'Place', 'Address', 37, 127)
                """, room, id, category.name(), id);
        jdbc.update("""
                INSERT INTO ballot_candidate (room_id, round_no, candidate_id, mode) VALUES (?, 1, ?, 'VOTE')
                """, room, id);
        jdbc.update("""
                INSERT INTO result_place (room_id, round_no, candidate_id, result_status, like_count,
                    neutral_count, dislike_count, score)
                VALUES (?, 1, ?, ?, 1, 0, 0, 2)
                """, room, id, status);
    }

    private void mockRoom(AtomicReference<Instant> now) {
        var confirmed = confirmedVenueRoom();
        var room = ROOM_ID.toString();
        when(rooms.lockForWrite(eq(ROOM_ID), any())).thenAnswer(call -> {
            jdbc.queryForObject("SELECT room_id FROM room WHERE room_id = ? FOR UPDATE", String.class, room);
            var actor = call.getArgument(1, RoomVenueAccess.Actor.class);
            var member = actor instanceof RoomVenueAccess.UserActor user
                    ? confirmedVenueMember(user.userId()) : Optional.<RoomMember>empty();
            var snapshot = new RoomVenueAccess.RoomVenueSnapshot(ROOM_ID, phase(), 1, 3, member,
                    confirmed.memberParticipantIds(), confirmed.selectedMeeting(), now.get());
            return (RoomVenueAccess.LockedRoomVenue) () -> snapshot;
        });
        doAnswer(call -> {
            var lock = call.getArgument(0, RoomVenueAccess.LockedRoomVenue.class);
            var next = call.getArgument(1, RoomPhase.class);
            var previous = lock.snapshot().phase();
            if (previous != next && !previous.canTransitionTo(next)) {
                throw new IllegalStateException("Invalid test workflow transition " + previous + " -> " + next);
            }
            jdbc.update("UPDATE room SET phase = ? WHERE room_id = ?", next.name(), room);
            return null;
        }).when(rooms).recordChange(any(), any());
    }

    private Optional<RoomMember> confirmedVenueMember(UUID user) {
        return Optional.of(jdbc.queryForObject("""
                SELECT participant_id, role FROM room_member WHERE room_id = ? AND user_id = ?
                """, (rs, row) -> new RoomMember(UUID.fromString(rs.getString(1)),
                RoomMember.Role.valueOf(rs.getString(2))),
                ROOM_ID.toString(), user.toString()));
    }

    private RoomPhase phase() {
        return RoomPhase.valueOf(jdbc.queryForObject("SELECT phase FROM room WHERE room_id = ?",
                String.class, ROOM_ID.toString()));
    }
}
