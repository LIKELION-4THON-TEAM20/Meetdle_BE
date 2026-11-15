package com.likelion4thon.Meetdle.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BoundaryFixturesTests {
    @Test
    void buildsAConfirmedRoomWithAJoinedMemberOutsideThePlanningSnapshot() {
        var room = BoundaryFixtures.confirmedVenueRoom();
        var snapshot = BoundaryFixtures.planningInputs();
        var userId = BoundaryFixtures.HOST.userId();
        var access = BoundaryFixtures.roomVenueAccess(userId, room);
        var locked = access.lockForWrite(room.roomId(), new RoomVenueAccess.UserActor(userId));

        assertEquals(room, locked.snapshot());
        assertTrue(room.memberParticipantIds().contains(BoundaryFixtures.MEMBER_WITHOUT_PLANNING_INPUT));
        assertFalse(snapshot.completedInputs().stream()
                .anyMatch(member -> member.participantId().equals(BoundaryFixtures.MEMBER_WITHOUT_PLANNING_INPUT)));
        assertEquals(4, room.capacity());
        assertEquals(3, room.memberParticipantIds().size());
    }
}
