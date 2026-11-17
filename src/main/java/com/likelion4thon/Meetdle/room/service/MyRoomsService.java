package com.likelion4thon.Meetdle.room.service;

import com.likelion4thon.Meetdle.common.error.ApiException;
import com.likelion4thon.Meetdle.common.time.ServiceZone;
import com.likelion4thon.Meetdle.contract.AuthenticatedUser;
import com.likelion4thon.Meetdle.contract.RoomPhase;
import com.likelion4thon.Meetdle.room.domain.MyRoomRow;
import com.likelion4thon.Meetdle.room.dto.DateRange;
import com.likelion4thon.Meetdle.room.dto.MyRoomSummary;
import com.likelion4thon.Meetdle.room.dto.MyRoomsPage;
import com.likelion4thon.Meetdle.room.repository.RoomRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A24 내 약속 목록 (홈 화면 SC-16).
 *
 * 📚 나눔·정렬 규칙 (명세 A24)
 * - PAST(지난 약속): 종료(ENDED)된 방, 확정한 약속이 끝난 방, 확정 없이 후보 기간이 지난 방
 * - UPCOMING(다가오는 약속): 나머지
 * - 다가오는 방은 "확정 시각 또는 후보 시작일"이 빠른 순, 지난 방은 "끝난 시각 또는 후보 종료일"이 최근인 순
 * - 같으면 방 UUID 순서로 줄 세운다 (그래야 페이지를 넘겨도 순서가 안 흔들린다)
 * - ALL은 다가오는 방들 다음에 지난 방들 (이 순서는 명세에 없어서 내가 정했다)
 */
@Service
public class MyRoomsService {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;
    private static final int UPCOMING = 0;
    private static final int PAST = 1;

    private final RoomRepository roomRepository;
    private final Clock clock;

    public MyRoomsService(RoomRepository roomRepository, Clock clock) {
        this.roomRepository = roomRepository;
        this.clock = clock;
    }

    public enum Scope {
        ALL, UPCOMING, PAST
    }

    @Transactional(readOnly = true)
    public MyRoomsPage list(AuthenticatedUser user, String scopeParam, Integer limitParam, String cursorParam) {
        Scope scope = parseScope(scopeParam);
        int limit = limitParam == null ? DEFAULT_LIMIT : limitParam;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.invalidRequest("limit", "1~" + MAX_LIMIT + " 사이여야 해요");
        }

        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ServiceZone.SEOUL);
        List<Entry> sorted = roomRepository.findRoomsOfUser(user.userId()).stream()
                .map(row -> Entry.of(row, now, today))
                .filter(entry -> scope == Scope.ALL || entry.key().group() == (scope == Scope.UPCOMING ? UPCOMING : PAST))
                .sorted(Comparator.comparing(Entry::key, KEY_ORDER))
                .toList();

        int start = 0;
        if (cursorParam != null && !cursorParam.isBlank()) {
            Key after = Cursor.decode(cursorParam, scope, user.userId());
            while (start < sorted.size() && KEY_ORDER.compare(sorted.get(start).key(), after) <= 0) {
                start++;
            }
        }
        int end = Math.min(start + limit, sorted.size());
        List<Entry> page = sorted.subList(start, end);
        String nextCursor = end < sorted.size()
                ? Cursor.encode(scope, user.userId(), page.get(page.size() - 1).key())
                : null;
        return new MyRoomsPage(page.stream().map(Entry::summary).toList(), nextCursor);
    }

    private static Scope parseScope(String value) {
        if (value == null || value.isBlank()) {
            return Scope.ALL;
        }
        try {
            return Scope.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidRequest("scope", "ALL, UPCOMING, PAST 중 하나여야 해요");
        }
    }

    /**
     * 줄 세우기 기준.
     * @param group      0 = 다가오는 방, 1 = 지난 방
     * @param sortMicros 다가오는 방은 시작 시각, 지난 방은 끝 시각 (마이크로초)
     */
    record Key(int group, long sortMicros, String roomId) {}

    /** 다가오는 방 먼저 → (다가오는 방은 빠른 순 / 지난 방은 최근 순) → 방 UUID 순 */
    private static final Comparator<Key> KEY_ORDER = (a, b) -> {
        if (a.group() != b.group()) {
            return Integer.compare(a.group(), b.group());
        }
        int byTime = a.group() == UPCOMING
                ? Long.compare(a.sortMicros(), b.sortMicros())
                : Long.compare(b.sortMicros(), a.sortMicros());
        return byTime != 0 ? byTime : a.roomId().compareTo(b.roomId());
    };

    private record Entry(Key key, MyRoomSummary summary) {

        static Entry of(MyRoomRow row, Instant now, LocalDate today) {
            boolean past = row.phase() == RoomPhase.ENDED
                    || (row.endsAt() != null && !row.endsAt().isAfter(now))
                    || (row.meetingAt() == null && row.endsOn().isBefore(today));
            Instant sortTime = past
                    ? (row.endsAt() != null ? row.endsAt() : startOfDay(row.endsOn().plusDays(1)))
                    : (row.meetingAt() != null ? row.meetingAt() : startOfDay(row.startsOn()));
            Key key = new Key(past ? PAST : UPCOMING, ChronoUnit.MICROS.between(Instant.EPOCH, sortTime),
                    row.roomId().toString());
            MyRoomSummary summary = new MyRoomSummary(row.roomId(), row.title(),
                    new DateRange(row.startsOn(), row.endsOn()), row.capacity(), row.joinedCount(), row.phase(),
                    row.meetingAt(), row.endsAt());
            return new Entry(key, summary);
        }

        private static Instant startOfDay(LocalDate date) {
            return date.atStartOfDay(ServiceZone.SEOUL).toInstant();
        }
    }

    /**
     * 커서 = "여기까지 봤어" 책갈피 🔖. 마지막으로 본 방의 줄 세우기 기준을 글자로 바꾼 것.
     * 다른 사람의 커서나 다른 scope의 커서를 넣으면 400 INVALID_CURSOR (명세: 커서는 사용자·필터·정렬 키에 묶는다).
     */
    static final class Cursor {
        private static final String VERSION = "v1";

        static String encode(Scope scope, UUID userId, Key key) {
            String raw = String.join("|", VERSION, scope.name(), userId.toString(),
                    String.valueOf(key.group()), String.valueOf(key.sortMicros()), key.roomId());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Key decode(String cursor, Scope scope, UUID userId) {
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
                if (parts.length != 6 || !VERSION.equals(parts[0])
                        || !scope.name().equals(parts[1]) || !userId.toString().equals(parts[2])) {
                    throw invalidCursor();
                }
                return new Key(Integer.parseInt(parts[3]), Long.parseLong(parts[4]), UUID.fromString(parts[5]).toString());
            } catch (IllegalArgumentException e) { // base64·숫자·UUID 형식 오류 모두 여기로
                throw invalidCursor();
            }
        }

        private static ApiException invalidCursor() {
            return ApiException.of(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "목록 위치 정보가 올바르지 않아요. 처음부터 다시 불러와 주세요.");
        }
    }
}
