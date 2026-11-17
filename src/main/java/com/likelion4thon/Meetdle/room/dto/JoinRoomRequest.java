package com.likelion4thon.Meetdle.room.dto;

import jakarta.validation.constraints.NotBlank;

/** A07 요청. 예: {"joinProof":"xY9...(A25에서 받은 값)"} */
public record JoinRoomRequest(@NotBlank(message = "필수 값이에요") String joinProof) {}
