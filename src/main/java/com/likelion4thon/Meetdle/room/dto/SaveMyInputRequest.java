package com.likelion4thon.Meetdle.room.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
        @Schema(description = "출발역 검색 응답의 stationId")
        UUID originStationId,
        @Schema(description = "약속 후 이동할 목적지역 검색 응답의 stationId")
        UUID destinationStationId,
        @Schema(description = "참가 가능한 모든 1시간 구간. 기존 가능 시간 목록을 전체 교체")
        @NotNull(message = "필수 값이에요") List<@Valid @NotNull HourSlot> slots,
        @Schema(description = "방 상태의 myInput.inputRevision 또는 직전 저장 응답의 입력 버전. 최초 저장은 0", example = "0")
        @NotNull(message = "필수 값이에요") @Min(value = 0, message = "0 이상이어야 해요") Long inputRevision,
        @Schema(description = "true는 입력 완료, false는 임시 저장. 완료 후 수정 불가", example = "false")
        @NotNull(message = "필수 값이에요") Boolean complete
) {}
