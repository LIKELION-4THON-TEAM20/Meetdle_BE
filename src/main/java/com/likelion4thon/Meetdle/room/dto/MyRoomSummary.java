package com.likelion4thon.Meetdle.room.dto;

import com.likelion4thon.Meetdle.contract.RoomPhase;

import java.time.Instant;
import java.util.UUID;

/**
 * 명세 공통 타입 MyRoomSummary. 홈 화면(SC-16) 약속 카드 하나.
 * 예: {"roomId":"3f2a...","title":"금요 저녁","dates":{"from":"2026-11-20","through":"2026-11-22"},
 *      "capacity":4,"joinedCount":3,"phase":"WAITING_FOR_DEPARTURE","meetingAt":null,"endsAt":null}
 */
public record MyRoomSummary(
        UUID roomId,
        String title,
        DateRange dates,
        int capacity,
        int joinedCount,
        RoomPhase phase,
        Instant meetingAt,
        Instant endsAt
) {}
