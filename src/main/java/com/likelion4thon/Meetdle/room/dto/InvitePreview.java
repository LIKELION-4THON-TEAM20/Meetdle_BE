package com.likelion4thon.Meetdle.room.dto;

import java.util.UUID;
import java.util.List;

/**
 * 명세 공통 타입 InvitePreview. 초대 화면(SC-03)에서 "이 방에 들어갈래?"를 보여줄 때 쓴다.
 * 현재 참여자의 방별 ID·닉네임·프로필 사진을 포함하며 코드·개인 입력은 담지 않는다.
 * 예: {"roomId":"3f2a...","title":"금요 저녁","dates":{"from":"2026-11-20","through":"2026-11-22"},
 *      "hostNickname":"은서","joinedCount":2,"capacity":4,"entryStatus":"OPEN"}
 */
public record InvitePreview(
        UUID roomId,
        String title,
        DateRange dates,
        String hostNickname,
        int joinedCount,
        int capacity,
        EntryStatus entryStatus,
        List<ParticipantProfile> members
) {
    public InvitePreview {
        members = List.copyOf(members);
    }

    public enum EntryStatus {
        /** 가입 가능 */
        OPEN,
        /** 정원이 꽉 참. 예: 4/4명 */
        FULL,
        /** 방장이 추천 계산을 시작해서 더는 가입 불가 */
        CLOSED,
        /** 보는 사람이 이미 이 방 회원 */
        MEMBER
    }
}
