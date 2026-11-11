package com.likelion4thon.Meetdle.contract;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface RoomVenueAccess {
    /** Reads a consistent room snapshot after verifying membership. */
    RoomVenueSnapshot read(UUID roomId, UUID userId);

    /** Locks the room row in the caller's active write transaction before reading the snapshot. */
    LockedRoomVenue lockForWrite(UUID roomId, Actor actor);

    /**
     * Records a successful mutation once in the same transaction as the venue write.
     * An unchanged phase still advances the revision if venue data changed.
     */
    void recordChange(LockedRoomVenue context, RoomPhase nextPhase);

    sealed interface Actor permits UserActor, SystemActor {}

    record UserActor(UUID userId) implements Actor {
        public UserActor {
            Objects.requireNonNull(userId, "userId");
        }
    }

    enum SystemActor implements Actor {
        DEADLINE_SCHEDULER
    }

    /** Handle returned by the provider. It is valid only in the transaction that obtained the lock. */
    interface LockedRoomVenue {
        RoomVenueSnapshot snapshot();
    }

    record RoomVenueSnapshot(
            UUID roomId,
            RoomPhase phase,
            long revision,
            int capacity,
            Optional<RoomMember> actorMember,
            Set<UUID> decisionParticipantIds,
            Optional<SelectedMeeting> selectedMeeting,
            Instant now
    ) {
        public RoomVenueSnapshot {
            Objects.requireNonNull(roomId, "roomId");
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(actorMember, "actorMember");
            decisionParticipantIds = Set.copyOf(decisionParticipantIds);
            Objects.requireNonNull(selectedMeeting, "selectedMeeting");
            Objects.requireNonNull(now, "now");
        }
    }
}
