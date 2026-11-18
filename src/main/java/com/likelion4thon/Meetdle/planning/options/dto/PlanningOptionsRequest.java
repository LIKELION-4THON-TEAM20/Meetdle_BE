package com.likelion4thon.Meetdle.planning.options.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** A11 요청 본문. 예: {"inputVersion": 5} = "방장 화면에 보이는 입력 버전은 5예요" */
public record PlanningOptionsRequest(
        @Schema(description = "추천 계산 직전에 조회한 방 상태의 inputVersion")
        @NotNull @Min(0) Long inputVersion
) {}
