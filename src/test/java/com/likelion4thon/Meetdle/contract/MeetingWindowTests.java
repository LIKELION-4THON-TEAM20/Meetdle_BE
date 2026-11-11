package com.likelion4thon.Meetdle.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MeetingWindowTests {
    @Test
    void switchesRegistrationMinimumAtSixHoursAndReservesVotingTime() {
        Instant meetingAt = Instant.parse("2030-01-02T09:00:00Z");
        Instant sixHoursBefore = meetingAt.minus(Duration.ofHours(6));

        MeetingWindow normal = MeetingWindow.calculate(meetingAt, sixHoursBefore);
        MeetingWindow shortened = MeetingWindow.calculate(meetingAt, sixHoursBefore.plusSeconds(1));

        assertEquals(Duration.ofHours(1), normal.registrationMinimum());
        assertEquals(Duration.ofMinutes(30), shortened.registrationMinimum());
        assertEquals(Duration.ofMinutes(30), normal.votingMinimum());
        assertEquals(Instant.parse("2030-01-02T06:00:00Z"), normal.votingCutoff());
        assertEquals(Instant.parse("2030-01-02T05:29:00Z"), normal.registrationCutoff());
    }
}
