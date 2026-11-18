package com.likelion4thon.Meetdle.room.domain;

import com.likelion4thon.Meetdle.contract.RoomPhase;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 내 약속 목록(A24)에 쓰는 방 한 줄.
 *
 * @param meetingAt 확정한 약속 시작 시각. 확정 전이면 null
 * @param endsAt    확정한 약속 끝 시각. 확정 전이면 null
 */
public record MyRoomRow(
        UUID roomId,
        String title,
        LocalDate startsOn,
        LocalDate endsOn,
        int capacity,
        int joinedCount,
        RoomPhase phase,
        Instant meetingAt,
        Instant endsAt
) {}
