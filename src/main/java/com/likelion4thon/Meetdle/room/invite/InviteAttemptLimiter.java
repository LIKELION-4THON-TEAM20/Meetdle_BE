package com.likelion4thon.Meetdle.room.invite;

import com.likelion4thon.Meetdle.common.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A25 코드 찍어보기 방지. 6자리 코드는 아무거나 계속 넣어보면 언젠가 맞을 수 있으니까 막는다.
 * 규칙(추측으로 정한 값): 한 사용자가 최근 10분 안에 5번 틀리면, 가장 오래된 실패가 10분 지날 때까지 429.
 * 맞히면 실패 기록을 지운다.
 *
 * 서버 메모리에 기록하므로 서버를 다시 켜면 초기화된다. 서버 1대로 운영하는 해커톤 규모에서는 충분하다.
 */
@Component
public class InviteAttemptLimiter {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(10);

    private final Map<UUID, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    public InviteAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    /** 예: 9:00~9:04 사이 5번 틀림 → 9:05에 다시 시도하면 429, retryAfterSeconds = 300 (9:10에 풀림) */
    public void checkAllowed(UUID userId) {
        Deque<Instant> history = failures.get(userId);
        if (history == null) {
            return;
        }
        Instant now = clock.instant();
        synchronized (history) {
            dropOld(history, now);
            if (history.size() >= MAX_FAILURES) {
                long retryAfter = Math.max(1, Duration.between(now, history.peekFirst().plus(WINDOW)).toSeconds());
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                        "코드를 여러 번 틀렸어요. 잠시 후 다시 시도해 주세요.",
                        Map.of("retryAfterSeconds", retryAfter));
            }
        }
    }

    public void recordFailure(UUID userId) {
        Deque<Instant> history = failures.computeIfAbsent(userId, id -> new ArrayDeque<>());
        Instant now = clock.instant();
        synchronized (history) {
            dropOld(history, now);
            history.addLast(now);
        }
    }

    public void recordSuccess(UUID userId) {
        failures.remove(userId);
    }

    private static void dropOld(Deque<Instant> history, Instant now) {
        while (!history.isEmpty() && !history.peekFirst().plus(WINDOW).isAfter(now)) {
            history.pollFirst();
        }
    }
}
