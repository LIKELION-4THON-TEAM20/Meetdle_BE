package com.likelion4thon.Meetdle.room.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * SELECT ... FOR UPDATE로 잠근 방 행. 트랜잭션이 끝날 때까지 다른 요청은 이 방 행을 바꾸지 못한다.
 *
 * @param joinClosedAt   첫 추천 계산 시작 시각 (가입 마감). null이면 가입 가능
 * @param inputsClosedAt 첫 확정 시각 (방 전체 입력 잠금). null이면 입력 가능
 */
public record LockedRoomRow(
        UUID roomId,
        int capacity,
        LocalDate startsOn,
        LocalDate endsOn,
        Instant joinClosedAt,
        Instant inputsClosedAt
) {}
