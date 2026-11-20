package com.likelion4thon.Meetdle.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class OAuthAttemptsTests {
    private final Clock clock = mock(Clock.class);
    private final Instant now = Instant.parse("2026-11-20T12:00:00Z");
    private final OAuthAttempts attempts = new OAuthAttempts(clock);
    private final MockHttpSession session = new MockHttpSession();

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(now);
    }

    @Test
    void keepsConcurrentReturnPathsAndConsumesEachStateOnce() {
        attempts.save(session, "first", "https://meetdle.co.kr", "/invite/first");
        attempts.save(session, "second", "http://localhost:5173", "/rooms");
        var first = attempts.consume(session, "first");
        assertThat(first.failure()).isNull();
        assertThat(first.attempt().path()).isEqualTo("/invite/first");
        assertThat(attempts.consume(session, "first").failure()).isEqualTo(OAuthAttempts.Failure.STATE_NOT_FOUND);
        var second = attempts.consume(session, "second");
        assertThat(second.attempt().origin()).isEqualTo("http://localhost:5173");
        assertThat(second.attempt().path()).isEqualTo("/rooms");
        assertThat(attempts.consume(session, "second").failure()).isEqualTo(OAuthAttempts.Failure.NO_PENDING_ATTEMPT);
    }

    @Test
    void rejectsMissingSessionAndStateFromAnotherSessionWithoutConsumingOriginal() {
        attempts.save(session, "state", "https://meetdle.co.kr", "/rooms");
        assertThat(attempts.consume(null, "state").failure()).isEqualTo(OAuthAttempts.Failure.NO_SESSION);
        assertThat(attempts.consume(new MockHttpSession(), "state").failure())
                .isEqualTo(OAuthAttempts.Failure.NO_PENDING_ATTEMPT);
        assertThat(attempts.consume(session, "state").attempt()).isNotNull();
    }

    @Test
    void rejectsMissingAndUnmatchedStateWithoutConsumingValidAttempt() {
        attempts.save(session, "state", "https://meetdle.co.kr", "/rooms");
        assertThat(attempts.consume(session, null).failure()).isEqualTo(OAuthAttempts.Failure.MISSING_STATE);
        assertThat(attempts.consume(session, " ").failure()).isEqualTo(OAuthAttempts.Failure.MISSING_STATE);
        assertThat(attempts.consume(session, "wrong").failure()).isEqualTo(OAuthAttempts.Failure.STATE_NOT_FOUND);
        assertThat(attempts.consume(session, "state").attempt()).isNotNull();
    }

    @Test
    void acceptsStateImmediatelyBeforeFiveMinuteExpiry() {
        attempts.save(session, "state", "https://meetdle.co.kr", "/rooms");
        when(clock.instant()).thenReturn(now.plusSeconds(300).minusNanos(1));
        assertThat(attempts.consume(session, "state").attempt()).isNotNull();
    }

    @Test
    void rejectsAndConsumesStateAtFiveMinuteExpiry() {
        attempts.save(session, "state", "https://meetdle.co.kr", "/rooms");
        when(clock.instant()).thenReturn(now.plusSeconds(300));
        assertThat(attempts.consume(session, "state").failure()).isEqualTo(OAuthAttempts.Failure.EXPIRED);
        assertThat(attempts.consume(session, "state").attempt()).isNull();
    }

    @Test
    void boundsPendingLoginsWhileKeepingTheEightMostRecent() {
        for (int i = 0; i < 9; i++) {
            attempts.save(session, "state-" + i, "https://meetdle.co.kr", "/rooms");
        }
        assertThat(attempts.consume(session, "state-0").attempt()).isNull();
        for (int i = 1; i < 9; i++) {
            assertThat(attempts.consume(session, "state-" + i).attempt()).isNotNull();
        }
    }
}
