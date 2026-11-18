package com.likelion4thon.Meetdle.room.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * A25 요청. 예: {"code":" k7p2qx "} 또는 링크로 들어온 경우 {"code":"K7P2QX","inviteToken":"Ab3...(43자)"}
 *
 * @param inviteToken 링크로 들어왔을 때만. 있으면 코드가 이 링크와 같은 초대인지도 확인한다.
 */
public record ResolveInviteRequest(
        @Schema(description = "초대 화면에 표시되거나 방장이 공유한 초대 코드")
        @NotBlank(message = "필수 값이에요") String code,
        @Schema(description = "초대 URL의 경로 토큰. 링크로 진입한 경우 코드와 함께 전달")
        String inviteToken
) {}
