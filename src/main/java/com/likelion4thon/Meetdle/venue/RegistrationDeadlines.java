package com.likelion4thon.Meetdle.venue;

import com.likelion4thon.Meetdle.contract.RoomVenueAccess;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
class RegistrationDeadlines {
    private final ObjectProvider<RoomVenueAccess> rooms;
    private final RegistrationStore store;
    private final PlaceRegistration registration;

    private final PlaceVoting voting;

    RegistrationDeadlines(ObjectProvider<RoomVenueAccess> rooms, RegistrationStore store,
                          PlaceRegistration registration, PlaceVoting voting) {
        this.rooms = rooms;
        this.store = store;
        this.registration = registration;
        this.voting = voting;
    }

    @Scheduled(fixedDelayString = "${meetdle.venue.deadline-poll-ms:15000}")
    void advance() {
        if (rooms.getIfAvailable() == null) {
            return;
        }
        var due = store.jdbc().query("""
                SELECT r.room_id FROM room r JOIN venue_round v ON v.room_id = r.room_id
                  AND v.round_no = (SELECT MAX(latest.round_no) FROM venue_round latest WHERE latest.room_id = r.room_id)
                WHERE r.phase IN ('PLACE_SUBMISSION_OPEN', 'PLACE_SUBMISSION_CLOSED')
                  AND v.collection_closes_at <= ?
                """, (rs, row) -> UUID.fromString(rs.getString(1)), RegistrationStore.utc(Instant.now()));
        advanceRooms(due, registration::advanceDeadline);
        var votingDue = store.jdbc().query("""
                SELECT v.room_id FROM venue_round v
                JOIN room r ON r.room_id = v.room_id
                WHERE v.round_no = (SELECT MAX(latest.round_no) FROM venue_round latest WHERE latest.room_id = v.room_id)
                  AND ((v.vote_closed_at IS NULL AND v.vote_closes_at <= ?)
                    OR v.recovery_reason = 'NO_ELIGIBLE_RESULT'
                    OR r.phase = 'WAITING_FOR_HOST_DECISION')
                """, (rs, row) -> UUID.fromString(rs.getString(1)), RegistrationStore.utc(Instant.now()));
        advanceRooms(votingDue, voting::advanceDeadline);
    }

    private void advanceRooms(java.util.List<UUID> due, java.util.function.Consumer<UUID> advance) {
        for (UUID room : due) {
            try {
                advance.accept(room);
            } catch (RuntimeException failure) {
                LoggerFactory.getLogger(RegistrationDeadlines.class)
                        .warn("Unable to advance venue deadline for room {}", room, failure);
            }
        }
    }
}
