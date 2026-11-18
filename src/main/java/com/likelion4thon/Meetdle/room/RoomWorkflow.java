package com.likelion4thon.Meetdle.room;

import com.likelion4thon.Meetdle.contract.RoomMember;
import com.likelion4thon.Meetdle.contract.RoomPhase;
import com.likelion4thon.Meetdle.contract.RoomVenueAccess.Actor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Room operations used by planning and the room-to-venue adapter. */
public interface RoomWorkflow {
    /** Locks the room row in the caller's active write transaction. */
    LockedRoom lockForWrite(UUID roomId, Actor actor);

    /**
     * Closes joining and captures the completed inputs for a host request matching the expected
     * input version. At least two completed inputs are required.
     */
    PlanningInputSnapshot beginPlanning(LockedRoom context, long expectedInputVersion);

    /** Loads only the completed inputs frozen by the initial planning request. */
    List<MemberInput> readCompletedInputs(UUID roomId, Set<UUID> participantIds);

    MemberInput saveMemberInput(LockedRoom context, long expectedInputRevision, InputValues values, boolean complete);

    /** Advances the room revision and closes input editing when the first meeting is confirmed. */
    void recordChange(LockedRoom context, RoomPhase nextPhase);

    interface LockedRoom {
        LockedRoomSnapshot snapshot();
    }

    record LockedRoomSnapshot(
            UUID roomId,
            RoomPhase phase,
            long revision,
            long inputVersion,
            int capacity,
            Optional<RoomMember> actorMember,
            Set<UUID> memberParticipantIds
    ) {
        public LockedRoomSnapshot {
            memberParticipantIds = Set.copyOf(memberParticipantIds);
        }
    }

    record PlanningInputSnapshot(long inputVersion, List<MemberInput> completedInputs) {
        public PlanningInputSnapshot {
            completedInputs = List.copyOf(completedInputs);
        }
    }

    record MemberInput(UUID participantId, long inputRevision, InputValues values, Optional<Instant> completedAt) {}

    record InputValues(Optional<UUID> originStationId, Optional<UUID> destinationStationId, List<HourSlot> slots) {
        public InputValues {
            slots = List.copyOf(slots);
        }
    }

    record HourSlot(LocalDate date, int hour) {}
}
