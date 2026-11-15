package com.likelion4thon.Meetdle.room.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 새로 저장할 방 한 행(room 테이블).
 * 검사를 모두 통과한 값만 담는다. phase, revision 같은 나머지 칸은 DB 기본값을 쓴다.
 *
 * @param title 없으면 null
 */
public record NewRoom(UUID roomId, String title, LocalDate startsOn, LocalDate endsOn, int capacity) {}
