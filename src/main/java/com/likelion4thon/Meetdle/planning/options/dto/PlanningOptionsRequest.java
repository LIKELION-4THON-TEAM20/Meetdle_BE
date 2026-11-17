package com.likelion4thon.Meetdle.planning.options.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** A11 요청 본문. 예: {"inputVersion": 5} = "방장 화면에 보이는 입력 버전은 5예요" */
public record PlanningOptionsRequest(@NotNull @Min(0) Long inputVersion) {}
