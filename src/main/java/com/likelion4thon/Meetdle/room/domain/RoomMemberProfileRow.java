package com.likelion4thon.Meetdle.room.domain;

import java.util.UUID;

/** 방별 참여자 프로필 조회 행. 사용자 계정 ID·개인 입력은 포함하지 않는다. */
public record RoomMemberProfileRow(UUID roomId, UUID participantId, String nickname, String profileImageUrl) {}
