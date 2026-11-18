package com.likelion4thon.Meetdle.room.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** 명세 공통 타입 DateRange {from, through}. 약속 후보 날짜 범위이며 양 끝을 포함한다. */
public record DateRange(
        @Schema(description = "후보 날짜 범위의 첫날. YYYY-MM-DD 형식")
        @NotNull(message = "필수 값이에요") LocalDate from,
        @Schema(description = "후보 날짜 범위의 마지막 날. YYYY-MM-DD 형식")
        @NotNull(message = "필수 값이에요") LocalDate through
) {}
