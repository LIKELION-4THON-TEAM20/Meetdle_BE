package com.likelion4thon.Meetdle.common.logging;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Structured diagnostics: identifiers, counts, flags and enums only; never request/provider bodies. */
public final class FeatureLog {
    private static final Logger log = LoggerFactory.getLogger(FeatureLog.class);

    private FeatureLog() {}

    public enum Event {
        KAKAO_PROFILE_PARSED, KAKAO_REQUEST_COMPLETED, KAKAO_REQUEST_FAILED, KAKAO_CATEGORY_EVALUATED,
        AUTH_ACCOUNT_SAVED, AUTH_SESSION_CREATED, AUTH_SESSION_READ,
        PHOTO_CONSENT_STARTED, PHOTO_CONSENT_FINISHED,
        INVITATION_PREVIEW_READ, INVITATION_RESOLVED, ROOM_JOINED, INPUT_SAVED,
        PLANNING_CALCULATED, MEETING_SELECTED, REGISTRATION_OPENED, PLACE_SEARCHED,
        NOMINATION_SAVED, NOMINATION_REMOVED, REGISTRATION_COMPLETED, REGISTRATION_EXTENDED,
        REGISTRATION_CLOSED, VOTING_OPENED, VOTE_SAVED, VOTE_SUBMITTED, VOTING_EXTENDED,
        VOTING_CLOSED, TIES_RESOLVED, VISIT_ORDER_CHANGED, RESULT_CONFIRMED,
        ROOM_STATE_READ, MY_ROOMS_READ
    }

    public enum Flag {
        YES, NO, UNKNOWN;

        public static Flag of(Object value) {
            return value instanceof Boolean flag ? (flag ? YES : NO) : UNKNOWN;
        }
    }

    public enum Provider { KAKAO_TOKEN, KAKAO_PROFILE, KAKAO_LOCAL }

    public enum CloseReason {
        ALL_COMPLETED, HOST_CLOSED, DEADLINE, ALL_SUBMITTED, HOST_PROCEED,
        NO_SUBMITTED_VOTES, DECISION_EXPIRED, UNKNOWN;

        public static CloseReason of(String value) {
            if (value == null) return UNKNOWN;
            try {
                return valueOf(value);
            } catch (IllegalArgumentException unknown) {
                return UNKNOWN;
            }
        }
    }

    public static void info(Event event, UUID roomId, Map<String, ?> fields) {
        write(Level.INFO, event, roomId, RequestOperationLog.currentRequestId(), safeFields(fields));
    }

    public static void debug(Event event, UUID roomId, Map<String, ?> fields) {
        if (log.isDebugEnabled()) {
            write(Level.DEBUG, event, roomId, RequestOperationLog.currentRequestId(), safeFields(fields));
        }
    }

    public static void warn(Event event, UUID roomId, Map<String, ?> fields) {
        write(Level.WARN, event, roomId, RequestOperationLog.currentRequestId(), safeFields(fields));
    }

    /** Capture request context now, but report successful mutations only after the transaction commits. */
    public static void committed(Event event, UUID roomId, Map<String, ?> fields) {
        String requestId = RequestOperationLog.currentRequestId();
        Map<String, Object> snapshot = safeFields(fields);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    write(Level.INFO, event, roomId, requestId, snapshot);
                }
            });
        } else {
            write(Level.INFO, event, roomId, requestId, snapshot);
        }
    }

    private static Map<String, Object> safeFields(Map<String, ?> fields) {
        var safe = new TreeMap<String, Object>();
        fields.forEach((key, value) -> {
            if (key != null && key.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
                Object sanitized = value instanceof Class<?> type ? type.getSimpleName()
                        : value instanceof Number || value instanceof Boolean || value instanceof UUID
                            || value instanceof Enum<?> ? value : "REDACTED";
                safe.put(key, sanitized);
            }
        });
        return Collections.unmodifiableMap(safe);
    }

    private static void write(Level level, Event event, UUID roomId, String requestId, Map<String, Object> fields) {
        log.atLevel(level).log("event={} requestId={} roomId={} fields={}",
                event.name().toLowerCase(Locale.ROOT), requestId, roomId == null ? "-" : roomId, fields);
    }
}
