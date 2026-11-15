package com.likelion4thon.Meetdle.contract;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.likelion4thon.Meetdle.room.RoomWorkflow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Fixed inputs for cross-module test doubles, using the IDs from base-room.sql. */
public final class BoundaryFixtures {
    public static final UUID ROOM_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    public static final AuthenticatedUser HOST = new AuthenticatedUser(
            UUID.fromString("00000000-0000-0000-0000-000000000001"), "방장");
    public static final UUID HOST_PARTICIPANT = UUID.fromString("20000000-0000-0000-0000-000000000001");
    public static final UUID MEMBER_PARTICIPANT = UUID.fromString("20000000-0000-0000-0000-000000000002");
    public static final UUID MEMBER_WITHOUT_PLANNING_INPUT =
            UUID.fromString("20000000-0000-0000-0000-000000000003");
    private static final UUID STATION_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    public static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    private BoundaryFixtures() {}

    public static RoomVenueAccess.RoomVenueSnapshot confirmedVenueRoom() {
        var hostMember = new RoomMember(HOST_PARTICIPANT, RoomMember.Role.HOST);
        var station = new SelectedMeeting.Station(
                STATION_ID, "서울역", List.of("1", "4"),
                37.5547, 126.9707);
        var meeting = new SelectedMeeting(
                UUID.fromString("40000000-0000-4000-8000-000000000001"), station,
                Instant.parse("2030-01-02T09:00:00Z"), Instant.parse("2030-01-02T10:00:00Z"));
        return new RoomVenueAccess.RoomVenueSnapshot(
                ROOM_ID, RoomPhase.REGION_CONFIRMED, 1, 4, Optional.of(hostMember),
                Set.of(HOST_PARTICIPANT, MEMBER_PARTICIPANT, MEMBER_WITHOUT_PLANNING_INPUT),
                Optional.of(meeting), NOW);
    }

    public static RoomWorkflow.PlanningInputSnapshot planningInputs() {
        var values = new RoomWorkflow.InputValues(Optional.of(STATION_ID), Optional.of(STATION_ID),
                List.of(new RoomWorkflow.HourSlot(LocalDate.parse("2030-01-02"), 18)));
        return new RoomWorkflow.PlanningInputSnapshot(2, List.of(
                new RoomWorkflow.MemberInput(HOST_PARTICIPANT, 1, values, Optional.of(NOW)),
                new RoomWorkflow.MemberInput(MEMBER_PARTICIPANT, 1, values, Optional.of(NOW))));
    }

    /** Returns a test double for the user's room reads and write locks. */
    public static RoomVenueAccess roomVenueAccess(UUID userId, RoomVenueAccess.RoomVenueSnapshot snapshot) {
        var access = mock(RoomVenueAccess.class);
        RoomVenueAccess.LockedRoomVenue lockedRoom = () -> snapshot;
        when(access.read(snapshot.roomId(), userId)).thenReturn(snapshot);
        when(access.lockForWrite(snapshot.roomId(), new RoomVenueAccess.UserActor(userId))).thenReturn(lockedRoom);
        return access;
    }

    public static VenueStateReader emptyVenueReader() {
        return context -> emptyVenueState();
    }

    public static VenueStateReader.VenueState emptyVenueState() {
        return new VenueStateReader.VenueState(null, null, null, null, null, null, List.of());
    }
}
