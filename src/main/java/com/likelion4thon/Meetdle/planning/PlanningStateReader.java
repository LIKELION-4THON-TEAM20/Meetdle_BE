package com.likelion4thon.Meetdle.planning;

import com.likelion4thon.Meetdle.contract.SelectedMeeting;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Reads the planning-owned data needed by the room-state assembler. */
public interface PlanningStateReader {
    /** Caller verifies host access before reading the stored options. */
    Optional<PlanningOptions> findOptions(UUID roomId);

    /** Resolves the verified viewer's own input station IDs for display. */
    Map<UUID, SelectedMeeting.Station> findStations(Set<UUID> stationIds);

    record PlanningOptions(UUID optionsId, long inputVersion, String matrixVersion,
                           List<SlotOption> slots, List<RegionCandidate> regions) {
        public PlanningOptions {
            slots = List.copyOf(slots);
            regions = List.copyOf(regions);
        }
    }

    record SlotOption(UUID slotId, Instant meetingAt, int availableCount, List<UUID> unavailableParticipantIds) {
        public SlotOption {
            unavailableParticipantIds = List.copyOf(unavailableParticipantIds);
        }

        public Instant endsAt() {
            return meetingAt.plus(Duration.ofHours(1));
        }
    }

    record RegionCandidate(UUID candidateId, SelectedMeeting.Station station,
                           double meanMinutes, double maxMinutes, double meanTransfers,
                           List<Reason> reasons, Score score, List<Travel> travel) {
        public RegionCandidate {
            reasons = List.copyOf(reasons);
            travel = List.copyOf(travel);
        }
    }

    record Score(double timeSpreadMinutes, double meanTotalTransfers) {}

    record Travel(UUID participantId, double minutes, int transfers) {}

    enum Reason {
        BALANCED, LOW_TRANSFER
    }
}
