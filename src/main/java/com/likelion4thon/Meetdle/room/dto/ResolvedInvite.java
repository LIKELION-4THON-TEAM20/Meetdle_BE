package com.likelion4thon.Meetdle.room.dto;

import java.time.Instant;

/**
 * A25 응답. joinProof는 "코드 확인을 통과했다"는 10분짜리 증표로, A07 가입 요청에 그대로 넣는다.
 * 증표는 서버 세션에 묶여 있어서 다른 브라우저(세션)에서는 쓸 수 없다.
 */
public record ResolvedInvite(InvitePreview preview, String joinProof, Instant joinProofExpiresAt) {}
