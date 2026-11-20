package com.likelion4thon.Meetdle.room.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A25 요청. 코드 또는 링크 토큰 중 하나 이상 필요.
 * 예: {"code":" k7p2qx "} 또는 {"inviteToken":"Ab3...(43자)"}
 *
 * @param inviteToken 코드 없이도 검증 가능. 코드와 함께 보내면 같은 초대인지 확인한다.
 */
public record ResolveInviteRequest(
        @Schema(description = "직접 입력한 초대 코드. code 또는 inviteToken 중 하나 이상 필수")
        String code,
        @Schema(description = "초대 URL의 43자 경로 토큰. 단독 전달 가능하며 코드도 있으면 동일 초대인지 확인")
        String inviteToken
) {}
