package com.likelion4thon.Meetdle.planning.options;

import com.likelion4thon.Meetdle.common.time.ServiceZone;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A11 일시 후보 고르기 🕐 (계산만 하는 부분)
 *
 * 방 날짜 × 10~23시의 1시간 칸마다 "가능한 사람 수"를 센다.
 * - 2명 이상 가능하고, 아직 지나지 않은 칸만
 * - 순서: 전원 가능 → 가능 인원 많은 순 → 빠른 시간 순, 최대 3개
 * 예: 3명 중 19시 3명, 20시 3명, 18시 2명, 21시 2명 → [19시, 20시, 18시]
 */
public final class SlotRecommender {

    static final int MAX_SLOTS = 3;
    static final int MIN_AVAILABLE = 2;
    static final int FIRST_HOUR = 10;
    static final int LAST_HOUR = 23;

    private SlotRecommender() {
    }

    /** meetingAt부터 1시간. unavailable = 이 칸에 안 되는 사람들 (참가자 ID 순). */
    public record Slot(Instant meetingAt, int availableCount, List<UUID> unavailableParticipantIds) {
        public Slot {
            unavailableParticipantIds = List.copyOf(unavailableParticipantIds);
        }
    }

    /**
     * @param availability 참가자 ID → 그 사람이 가능한 칸들 (서울 시각, 정각)
     */
    public static List<Slot> recommend(LocalDate from, LocalDate through,
                                       Map<UUID, Set<LocalDateTime>> availability, Instant now) {
        List<UUID> participants = availability.keySet().stream().sorted().toList();
        List<Slot> slots = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(through); date = date.plusDays(1)) {
            for (int hour = FIRST_HOUR; hour <= LAST_HOUR; hour++) {
                LocalDateTime local = LocalDateTime.of(date, LocalTime.of(hour, 0));
                Instant meetingAt = local.atZone(ServiceZone.SEOUL).toInstant();
                if (!meetingAt.isAfter(now)) {
                    continue;
                }
                List<UUID> unavailable = participants.stream()
                        .filter(p -> !availability.get(p).contains(local))
                        .toList();
                int available = participants.size() - unavailable.size();
                if (available >= MIN_AVAILABLE) {
                    slots.add(new Slot(meetingAt, available, unavailable));
                }
            }
        }
        return slots.stream()
                .sorted(Comparator.comparing((Slot s) -> !s.unavailableParticipantIds().isEmpty())
                        .thenComparing(Slot::availableCount, Comparator.reverseOrder())
                        .thenComparing(Slot::meetingAt))
                .limit(MAX_SLOTS)
                .toList();
    }
}
