package com.likelion4thon.Meetdle.room.domain;

import java.time.Instant;
import java.util.UUID;

/** 새로 저장할 초대 한 행(room_invitation 테이블). 원문 코드·토큰은 담지 않는다. */
public record NewInvitation(
        UUID invitationId,
        UUID roomId,
        byte[] tokenLookupHash,
        byte[] encryptedToken,
        byte[] codeLookupHash,
        byte[] encryptedCode,
        Instant expiresAt
) {}
