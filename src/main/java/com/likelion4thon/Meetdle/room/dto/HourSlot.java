package com.likelion4thon.Meetdle.room.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * 명세 공통 타입 HourSlot {date, hour}. 한 칸 = 1시간.
 * 예: {"date":"2026-11-20","hour":19} → 11/20 19:00~20:00 가능
 */
public record HourSlot(
        @NotNull(message = "필수 값이에요") LocalDate date,
        @NotNull(message = "필수 값이에요") Integer hour
) {}
