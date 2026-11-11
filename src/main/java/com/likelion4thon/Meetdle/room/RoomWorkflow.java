package com.likelion4thon.Meetdle.room;

import com.likelion4thon.Meetdle.contract.RoomMember;
import com.likelion4thon.Meetdle.contract.RoomPhase;
import com.likelion4thon.Meetdle.contract.RoomVenueAccess.Actor;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Room operations used by planning and the room-to-venue adapter. */
public interface RoomWorkflow {
    /** Checks host access and returns one consistent input version. */
    PlanningInputs readPlanningInputs(UUID roomId, UUID hostUserId);

    /** Locks the room row in the caller's active write transaction. */
    LockedRoom lockForWrite(UUID roomId, Actor actor);

    MemberInput saveMemberInput(LockedRoom context, long expectedInputRevision, InputValues values);

    Set<UUID> freezeDecisionRoster(LockedRoom context, long expectedInputVersion, Set<UUID> participantIds);

    /** Advances the room revision after a successful mutation. */
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
            Set<UUID> decisionParticipantIds
    ) {
        public LockedRoomSnapshot {
            decisionParticipantIds = Set.copyOf(decisionParticipantIds);
        }
    }

    record PlanningInputs(int capacity, LocalDate startsOn, LocalDate endsOn, long inputVersion, List<MemberInput> members) {
        public PlanningInputs {
            members = List.copyOf(members);
        }
    }

    record MemberInput(UUID participantId, long inputRevision, InputValues values, boolean complete) {}

    record InputValues(Optional<UUID> originStationId, Optional<UUID> destinationStationId, List<HourSlot> slots) {
        public InputValues {
            slots = List.copyOf(slots);
        }
    }

    record HourSlot(LocalDate date, int hour) {}
}
