package com.likelion4thon.Meetdle.room.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * A05 요청 본문.
 * 예: {"title":"금요 저녁","dates":{"from":"2026-11-20","through":"2026-11-22"},"capacity":4}
 *
 * 모양(빈 값, 숫자 범위)은 여기서 @Valid로 검사하고,
 * 날짜 길이·제목 길이처럼 계산이 필요한 규칙은 RoomService에서 검사한다.
 */
public record CreateRoomRequest(
        String title,

        @NotNull(message = "필수 값이에요")
        @Valid
        DateRange dates,

        @NotNull(message = "필수 값이에요")
        @Min(value = 2, message = "방장 포함 2명 이상이어야 해요")
        @Max(value = 6, message = "방장 포함 6명 이하여야 해요")
        Integer capacity
) {

    /** 약속 후보 날짜 범위. from과 through 둘 다 포함한다. */
    public record DateRange(
            @NotNull(message = "필수 값이에요") LocalDate from,
            @NotNull(message = "필수 값이에요") LocalDate through
    ) {}
}
