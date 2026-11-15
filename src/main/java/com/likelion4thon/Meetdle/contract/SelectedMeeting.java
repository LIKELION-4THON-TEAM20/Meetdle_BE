package com.likelion4thon.Meetdle.contract;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Confirmed meeting shared with venue and room-state composition. */
public record SelectedMeeting(UUID selectionId, Station station, Instant meetingAt, Instant endsAt) {
    public SelectedMeeting {
        Objects.requireNonNull(selectionId, "selectionId");
        Objects.requireNonNull(station, "station");
        Objects.requireNonNull(meetingAt, "meetingAt");
        if (!meetingAt.plus(Duration.ofHours(1)).equals(endsAt)) {
            throw new IllegalArgumentException("The confirmed slot must be one hour");
        }
    }

    public record Station(UUID stationId, String name, List<String> lines, double lat, double lng) {
        public Station {
            lines = List.copyOf(lines);
        }
    }
}
