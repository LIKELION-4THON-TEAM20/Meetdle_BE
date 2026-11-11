package com.likelion4thon.Meetdle.contract;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record SelectedMeeting(Meeting meeting, List<Travel> travel) {
    public SelectedMeeting {
        Objects.requireNonNull(meeting, "meeting");
        travel = List.copyOf(travel);
    }

    public record Meeting(
            UUID selectionId,
            Station station,
            Instant meetingAt,
            Instant endsAt,
            int availableCount,
            double meanMinutes,
            double maxMinutes,
            double meanTransfers
    ) {}

    public record Station(UUID stationId, String name, List<String> lines, double lat, double lng) {
        public Station {
            lines = List.copyOf(lines);
        }
    }

    public record Travel(UUID participantId, double minutes, int transfers) {}
}
