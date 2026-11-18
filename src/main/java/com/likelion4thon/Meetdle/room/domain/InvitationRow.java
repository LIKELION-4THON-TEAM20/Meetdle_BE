package com.likelion4thon.Meetdle.room.domain;

import java.time.Instant;
import java.util.UUID;

/** DB에서 읽은 초대 한 행 (코드·토큰 원문은 없음). */
public record InvitationRow(UUID invitationId, UUID roomId, Instant expiresAt) {

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
