package com.likelion4thon.Meetdle.room.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** 명세 공통 타입 DateRange {from, through}. 약속 후보 날짜 범위이며 양 끝을 포함한다. */
public record DateRange(
        @NotNull(message = "필수 값이에요") LocalDate from,
        @NotNull(message = "필수 값이에요") LocalDate through
) {}
