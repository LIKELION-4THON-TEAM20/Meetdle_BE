package com.likelion4thon.Meetdle.room.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 초대 미리보기(A06·A25)에 필요한 방 정보.
 *
 * @param joinClosedAt 첫 추천 계산을 시작하면 채워진다. null이면 아직 가입 가능.
 */
public record RoomPreviewRow(
        UUID roomId,
        String title,
        LocalDate startsOn,
        LocalDate endsOn,
        int capacity,
        Instant joinClosedAt,
        String hostNickname,
        int joinedCount
) {}
