package com.likelion4thon.Meetdle.room.domain;

import java.time.Instant;
import java.util.UUID;

/** SELECT ... FOR UPDATE로 잠근 방 행. 트랜잭션이 끝날 때까지 다른 요청은 이 방 행을 바꾸지 못한다. */
public record LockedRoomRow(UUID roomId, int capacity, Instant joinClosedAt) {}
