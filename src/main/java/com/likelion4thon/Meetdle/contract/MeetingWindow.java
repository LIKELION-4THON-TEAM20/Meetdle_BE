package com.likelion4thon.Meetdle.contract;

import java.time.Duration;
import java.time.Instant;

public record MeetingWindow(
        Duration registrationMinimum,
        Instant registrationCutoff,
        Duration votingMinimum,
        Instant votingCutoff
) {
    public static MeetingWindow calculate(Instant meetingAt, Instant now) {
        Duration timeRemaining = Duration.between(now, meetingAt);
        Duration registrationMinimum = timeRemaining.compareTo(Duration.ofHours(6)) >= 0
                ? Duration.ofHours(1)
                : Duration.ofMinutes(30);
        Duration votingMinimum = Duration.ofMinutes(30);
        Instant votingCutoff = meetingAt.minus(Duration.ofHours(3));
        Instant registrationCutoff = votingCutoff.minus(votingMinimum).minus(Duration.ofMinutes(1));
        return new MeetingWindow(registrationMinimum, registrationCutoff, votingMinimum, votingCutoff);
    }
}
