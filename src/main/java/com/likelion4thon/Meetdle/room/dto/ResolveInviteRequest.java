package com.likelion4thon.Meetdle.room.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * A25 요청. 예: {"code":" k7p2qx "} 또는 링크로 들어온 경우 {"code":"K7P2QX","inviteToken":"Ab3...(43자)"}
 *
 * @param inviteToken 링크로 들어왔을 때만. 있으면 코드가 이 링크와 같은 초대인지도 확인한다.
 */
public record ResolveInviteRequest(
        @NotBlank(message = "필수 값이에요") String code,
        String inviteToken
) {}
