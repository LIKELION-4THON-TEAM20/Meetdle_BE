package com.likelion4thon.Meetdle.venue;

import com.likelion4thon.Meetdle.contract.BoundaryFixtures;
import com.likelion4thon.Meetdle.contract.RoomVenueAccess.RoomVenueSnapshot;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RegistrationWindowTests {
    @Test
    void acceptsDeadlineAtThreeHourCutoffAndRejectsAnyLaterDeadline() {
        var fixture = BoundaryFixtures.confirmedVenueRoom();
        var meeting = fixture.selectedMeeting().orElseThrow();
        var cutoff = meeting.meetingAt().minus(Duration.ofHours(3));
        var snapshot = at(cutoff.minus(Duration.ofMinutes(30)));
        assertEquals(cutoff, PlaceRegistration.registrationDeadline(snapshot, meeting, null, false));
        assertEquals(cutoff, PlaceRegistration.registrationDeadline(snapshot, meeting, cutoff, true));
        assertEquals("INVALID_DEADLINE", assertThrows(VenueException.class,
                () -> PlaceRegistration.registrationDeadline(snapshot, meeting, cutoff.plusSeconds(1), true)).getMessage());
        assertEquals("INSUFFICIENT_TIME", assertThrows(VenueException.class,
                () -> PlaceRegistration.registrationDeadline(at(snapshot.now().plusSeconds(1)), meeting, null, false)).getMessage());
    }

    private static RoomVenueSnapshot at(Instant now) {
        var fixture = BoundaryFixtures.confirmedVenueRoom();
        return new RoomVenueSnapshot(fixture.roomId(), fixture.phase(), fixture.revision(), fixture.capacity(),
                fixture.actorMember(), fixture.memberParticipantIds(), fixture.selectedMeeting(), now);
    }
}
