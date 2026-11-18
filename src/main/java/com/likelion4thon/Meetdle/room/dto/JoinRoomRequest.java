package com.likelion4thon.Meetdle.room.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** A07 요청. 예: {"joinProof":"xY9...(A25에서 받은 값)"} */
public record JoinRoomRequest(
        @Schema(description = "같은 로그인 세션의 초대 코드 확인 응답에서 받은 방 참여 증표. 만료 전에 사용")
        @NotBlank(message = "필수 값이에요") String joinProof
) {}
