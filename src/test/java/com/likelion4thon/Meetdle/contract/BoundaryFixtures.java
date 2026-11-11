package com.likelion4thon.Meetdle.contract;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Fixed inputs for cross-module test doubles, using the IDs from base-room.sql. */
public final class BoundaryFixtures {
    public static final UUID ROOM_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    public static final AuthenticatedUser HOST = new AuthenticatedUser(
            UUID.fromString("00000000-0000-0000-0000-000000000001"), "방장");
    public static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    private BoundaryFixtures() {}

    public static RoomVenueAccess.RoomVenueSnapshot confirmedVenueRoom() {
        UUID hostParticipant = UUID.fromString("20000000-0000-0000-0000-000000000001");
        Set<UUID> decisionParticipants = Set.of(hostParticipant,
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                UUID.fromString("20000000-0000-0000-0000-000000000003"));
        var hostMember = new RoomMember(hostParticipant, RoomMember.Role.HOST, RoomMember.DecisionStatus.DECIDER);
        var station = new SelectedMeeting.Station(
                UUID.fromString("30000000-0000-4000-8000-000000000001"), "서울역", List.of("1", "4"),
                37.5547, 126.9707);
        var meeting = new SelectedMeeting.Meeting(
                UUID.fromString("40000000-0000-4000-8000-000000000001"), station,
                Instant.parse("2030-01-02T09:00:00Z"), Instant.parse("2030-01-02T11:00:00Z"),
                3, 24, 33, 1);
        return new RoomVenueAccess.RoomVenueSnapshot(
                ROOM_ID, RoomPhase.REGION_CONFIRMED, 1, 4, Optional.of(hostMember),
                decisionParticipants, Optional.of(new SelectedMeeting(meeting, List.of())), NOW);
    }

    public static VenueStateReader.VenueState emptyVenueState() {
        return new VenueStateReader.VenueState(null, null, null, null, null, null, null, List.of());
    }
}
