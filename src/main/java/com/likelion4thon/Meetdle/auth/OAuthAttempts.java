package com.likelion4thon.Meetdle.auth;

import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

/** Keeps concurrent login attempts separate, bound to the initiating browser session. */
@Component
class OAuthAttempts {
    private static final String ATTRIBUTE = OAuthAttempts.class.getName() + ".pending";
    private static final int MAX_PENDING = 8;
    private final Clock clock;

    OAuthAttempts(Clock clock) {
        this.clock = clock;
    }

    void save(HttpSession session, String state, String origin, String path) {
        save(session, state, origin, path, Purpose.LOGIN, null);
    }

    void savePhotoConsent(HttpSession session, String state, String origin, String path, UUID userId) {
        save(session, state, origin, path, Purpose.PHOTO_CONSENT, Objects.requireNonNull(userId));
    }

    private void save(HttpSession session, String state, String origin, String path, Purpose purpose, UUID userId) {
        synchronized (WebUtils.getSessionMutex(session)) {
            var attempts = pending(session);
            Instant now = clock.instant();
            attempts.values().removeIf(attempt -> !now.isBefore(attempt.expiresAt()));
            while (attempts.size() >= MAX_PENDING) {
                attempts.remove(attempts.keySet().iterator().next());
            }
            attempts.put(state, new Attempt(origin, path, now.plusSeconds(300), purpose, userId));
            session.setAttribute(ATTRIBUTE, new Pending(attempts));
        }
    }

    Verification consume(HttpSession session, String state) {
        if (session == null) {
            return new Verification(null, Failure.NO_SESSION);
        }
        if (state == null || state.isBlank()) {
            return new Verification(null, Failure.MISSING_STATE);
        }
        synchronized (WebUtils.getSessionMutex(session)) {
            var attempts = pending(session);
            if (attempts.isEmpty()) {
                return new Verification(null, Failure.NO_PENDING_ATTEMPT);
            }
            Attempt attempt = attempts.remove(state);
            if (attempt == null) {
                return new Verification(null, Failure.STATE_NOT_FOUND);
            }
            // Remove only this attempt, including when it has expired. Every callback is single use.
            if (attempts.isEmpty()) {
                session.removeAttribute(ATTRIBUTE);
            } else {
                session.setAttribute(ATTRIBUTE, new Pending(attempts));
            }
            if (!clock.instant().isBefore(attempt.expiresAt())) {
                return new Verification(null, Failure.EXPIRED);
            }
            return new Verification(attempt, null);
        }
    }

    private static LinkedHashMap<String, Attempt> pending(HttpSession session) {
        return session.getAttribute(ATTRIBUTE) instanceof Pending pending
                ? new LinkedHashMap<>(pending.attempts()) : new LinkedHashMap<>();
    }

    enum Failure { NO_SESSION, MISSING_STATE, NO_PENDING_ATTEMPT, STATE_NOT_FOUND, EXPIRED }
    enum Purpose { LOGIN, PHOTO_CONSENT }

    record Verification(Attempt attempt, Failure failure) {}
    record Attempt(String origin, String path, Instant expiresAt, Purpose purpose, UUID userId) implements Serializable {}
    private record Pending(Map<String, Attempt> attempts) implements Serializable {}
}
