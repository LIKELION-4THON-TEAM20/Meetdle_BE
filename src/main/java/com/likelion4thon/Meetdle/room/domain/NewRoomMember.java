package com.likelion4thon.Meetdle.room.domain;

import com.likelion4thon.Meetdle.contract.RoomMember.Role;

import java.util.UUID;

/**
 * 새로 저장할 방 참가자 한 행(room_member 테이블).
 * userId는 "카카오 로그인한 사람"의 ID, participantId는 "이 방 안에서의 참가자" ID다.
 * 같은 사람이 방 3개에 들어가면 userId는 1개, participantId는 3개가 된다.
 */
public record NewRoomMember(UUID participantId, UUID roomId, UUID userId, String nickname, Role role) {}
