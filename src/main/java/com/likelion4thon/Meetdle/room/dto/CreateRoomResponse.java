package com.likelion4thon.Meetdle.room.dto;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * A05 응답. 방장 본인에게만 가는 응답이라 초대 정보를 담는다.
 * 예: {"roomId":"3f2a...","invite":{"code":"K7P2QX","inviteUrl":"http://.../invite/Ab3...","expiresAt":"2026-11-22T09:00:00Z"}}
 */
public record CreateRoomResponse(UUID roomId, Invite invite) {

    /** 명세 공통 타입 Invite {code, inviteUrl, expiresAt} */
    public record Invite(String code, URI inviteUrl, Instant expiresAt) {}
}
