package com.likelion4thon.Meetdle.room.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * A05 요청 본문.
 * 예: {"title":"금요 저녁","dates":{"from":"2026-11-20","through":"2026-11-22"},"capacity":4}
 *
 * 모양(빈 값, 숫자 범위)은 여기서 @Valid로 검사하고,
 * 날짜 길이·제목 길이처럼 계산이 필요한 규칙은 RoomService에서 검사한다.
 */
public record CreateRoomRequest(
        @Schema(description = "방 제목. 생략 가능하며 입력 시 앞뒤 공백을 제외한 1~50자", example = "금요 저녁")
        String title,
        @Schema(description = "약속 후보 날짜 범위. 시작일과 종료일을 포함해 최대 7일")
        @NotNull(message = "필수 값이에요")
        @Valid
        DateRange dates,
        @Schema(description = "방장 포함 최대 참가 인원. 2~6명", example = "4")
        @NotNull(message = "필수 값이에요")
        @Min(value = 2, message = "방장 포함 2명 이상이어야 해요")
        @Max(value = 6, message = "방장 포함 6명 이하여야 해요")
        Integer capacity
) {}
