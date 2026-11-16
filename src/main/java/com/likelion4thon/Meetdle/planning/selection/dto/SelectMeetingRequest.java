package com.likelion4thon.Meetdle.planning.selection.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * A12 요청 본문.
 * 예(첫 확정): {"optionsId":"…","slotId":"…","candidateId":"…","expectedSelectionId":null}
 * 예(재선택):  expectedSelectionId = 지금 화면에 보이는 확정 선택 ID
 */
public record SelectMeetingRequest(
        @NotNull UUID optionsId,
        @NotNull UUID slotId,
        @NotNull UUID candidateId,
        UUID expectedSelectionId
) {}
