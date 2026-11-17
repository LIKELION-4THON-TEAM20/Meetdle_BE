package com.likelion4thon.Meetdle.room.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * A10 요청. 내 입력 전체를 이 값으로 바꾼다(부분 수정 아님).
 * 예: {"originStationId":"...강남","destinationStationId":"...홍대입구",
 *      "slots":[{"date":"2026-11-20","hour":19},{"date":"2026-11-20","hour":20}],
 *      "inputRevision":3,"complete":false}
 *
 * @param inputRevision 내가 마지막으로 받은 내 입력 버전. 처음이면 0. 서버 값과 다르면 409 STALE_INPUT
 * @param complete      true면 "입력 완료". 완료 뒤에는 더 못 바꾼다
 */
public record SaveMyInputRequest(
        UUID originStationId,
        UUID destinationStationId,
        @NotNull(message = "필수 값이에요") List<@Valid @NotNull HourSlot> slots,
        @NotNull(message = "필수 값이에요") @Min(value = 0, message = "0 이상이어야 해요") Long inputRevision,
        @NotNull(message = "필수 값이에요") Boolean complete
) {}
