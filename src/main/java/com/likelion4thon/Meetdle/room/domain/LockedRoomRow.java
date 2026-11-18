package com.likelion4thon.Meetdle.room.domain;

import com.likelion4thon.Meetdle.contract.RoomPhase;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * SELECT ... FOR UPDATE로 잠근 방 행. 트랜잭션이 끝날 때까지 다른 요청은 이 방 행을 바꾸지 못한다.
 *
 * @param joinClosedAt   첫 추천 계산 시작 시각 (가입 마감). null이면 가입 가능
 * @param inputsClosedAt 첫 확정 시각 (방 전체 입력 잠금). null이면 입력 가능
 * @param phase          방 진행 단계. 예: WAITING_FOR_DEPARTURE(조건 모으는 중)
 * @param inputVersion   누군가 입력을 바꿀 때마다 1씩 오르는 번호 (A11 계산 요청이 최신인지 확인)
 * @param currentSelectionId 지금 확정된 일시·지역 선택 (A12). null이면 아직 확정 전
 * @param revision       방에 무슨 변화가 생길 때마다 1씩 오르는 번호
 */
public record LockedRoomRow(
        UUID roomId,
        int capacity,
        LocalDate startsOn,
        LocalDate endsOn,
        Instant joinClosedAt,
        Instant inputsClosedAt,
        RoomPhase phase,
        long inputVersion,
        UUID currentSelectionId,
        long revision
) {}
