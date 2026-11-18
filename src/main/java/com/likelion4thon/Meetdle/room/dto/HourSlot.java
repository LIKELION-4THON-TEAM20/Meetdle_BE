package com.likelion4thon.Meetdle.room.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * 명세 공통 타입 HourSlot {date, hour}. 한 칸 = 1시간.
 * 예: {"date":"2026-11-20","hour":19} → 11/20 19:00~20:00 가능
 */
public record HourSlot(
        @Schema(description = "방의 후보 날짜 범위에 포함되는 날짜. YYYY-MM-DD 형식")
        @NotNull(message = "필수 값이에요") LocalDate date,
        @Schema(description = "한국 시간 기준 1시간 구간의 시작 시각. 10~23 정수, 19는 19:00~20:00", example = "19")
        @NotNull(message = "필수 값이에요") Integer hour
) {}
