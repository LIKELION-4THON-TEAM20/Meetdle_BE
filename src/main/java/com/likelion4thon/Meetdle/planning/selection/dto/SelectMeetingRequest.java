package com.likelion4thon.Meetdle.planning.selection.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * A12 요청 본문.
 * 예(첫 확정): {"optionsId":"…","slotId":"…","candidateId":"…","expectedSelectionId":null}
 * 예(재선택):  expectedSelectionId = 지금 화면에 보이는 확정 선택 ID
 */
public record SelectMeetingRequest(
        @Schema(description = "일시·지역 추천 응답의 추천 결과 UUID")
        @NotNull UUID optionsId,
        @Schema(description = "추천 응답에서 선택한 일시 후보 UUID")
        @NotNull UUID slotId,
        @Schema(description = "추천 응답에서 선택한 지역 후보 UUID")
        @NotNull UUID candidateId,
        @Schema(description = "최초 확정 시 null. 재선택 시 방 상태의 meeting.selectionId 사용")
        UUID expectedSelectionId
) {}
