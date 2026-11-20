package com.likelion4thon.Meetdle.room.dto;

import java.util.UUID;

/** 초대 미리보기·홈 약속 카드에 공개하는 현재 참여자의 표시 정보. 사진이 없으면 null. */
public record ParticipantProfile(UUID participantId, String nickname, String profileImageUrl) {}
