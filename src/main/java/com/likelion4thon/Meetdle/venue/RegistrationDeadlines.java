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

    RegistrationDeadlines(ObjectProvider<RoomVenueAccess> rooms, RegistrationStore store,
                          PlaceRegistration registration) {
        this.rooms = rooms;
        this.store = store;
        this.registration = registration;
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
        for (UUID room : due) {
            try {
                registration.advanceDeadline(room);
            } catch (RuntimeException failure) {
                LoggerFactory.getLogger(RegistrationDeadlines.class)
                        .warn("Unable to advance place registration deadline for room {}", room, failure);
            }
        }
    }
}
