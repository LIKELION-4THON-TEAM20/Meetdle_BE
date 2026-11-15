package com.likelion4thon.Meetdle.room.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * member_input 한 줄. 가능 시간(member_availability)은 따로 읽는다.
 *
 * @param originStationId      부분 저장이면 null
 * @param destinationStationId 부분 저장이면 null
 * @param completedAt          입력 완료를 누른 시각. null이면 아직 작성 중
 */
public record MemberInputRow(
        UUID participantId,
        UUID originStationId,
        UUID destinationStationId,
        long inputRevision,
        Instant completedAt
) {

    /** 아직 한 번도 저장 안 한 사람의 빈 입력 (revision 0). */
    public static MemberInputRow empty(UUID participantId) {
        return new MemberInputRow(participantId, null, null, 0, null);
    }
}
