package com.likelion4thon.Meetdle.venue;

import com.likelion4thon.Meetdle.contract.RoomMember;
import com.likelion4thon.Meetdle.contract.RoomPhase;
import com.likelion4thon.Meetdle.contract.RoomVenueAccess;
import com.likelion4thon.Meetdle.contract.VoteClosed;
import com.likelion4thon.Meetdle.contract.VenueStateReader.Selection;
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

import static com.likelion4thon.Meetdle.contract.BoundaryFixtures.*;
import static com.likelion4thon.Meetdle.contract.RoomPhase.*;
import static com.likelion4thon.Meetdle.contract.VenueStateReader.Choice.DISLIKE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:vote-recovery;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Sql("/fixtures/base-room.sql")
@RecordApplicationEvents
class VoteRecoveryTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlaceVoting voting;
    @Autowired ApplicationEvents events;
    @MockitoBean RoomVenueAccess rooms;

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
