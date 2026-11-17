package com.likelion4thon.Meetdle.room.dto;

import com.likelion4thon.Meetdle.contract.RoomMember;
import com.likelion4thon.Meetdle.contract.RoomPhase;
import com.likelion4thon.Meetdle.contract.SelectedMeeting;
import com.likelion4thon.Meetdle.contract.VenueStateReader;
import com.likelion4thon.Meetdle.planning.options.dto.PlanningOptionsResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A08 GET /api/rooms/{roomId}/state 응답 (명세 "공통 응답 타입"의 RoomState).
 * 단계마다 필요 없는 칸은 null. 예: 아직 장소 등록 전이면 registration = null.
 */
public record RoomState(
        UUID roomId,
        String title,
        DateRange dates,
        RoomPhase phase,
        long revision,
        long inputVersion,
        Instant serverNow,
        int joinedCount,
        int capacity,
        Progress inputProgress,
        MyMember me,
        List<MemberStatus> members,
        MyInput myInput,
        CreateRoomResponse.Invite invite,
        PlanningOptionsResponse options,
        SelectedMeeting meeting,
        Registration registration,
        Voting voting,
        VoteReview voteReview,
        TieDecision tieDecision,
        FinalResult result,
        VenueStateReader.Recovery recovery,
        List<String> allowedActions
) {

    /** 예: 정원 4명 중 2명 완료 → {completedCount: 2, capacity: 4} */
    public record Progress(int completedCount, int capacity) {}

    public record MyMember(UUID participantId, RoomMember.Role role, boolean canRegister, boolean canVote) {}

    public record MemberStatus(UUID participantId, String nickname, RoomMember.Role role, boolean inputComplete) {}

    /** 장소 등록·투표에서 이름별 완료 여부. 누가 뭘 골랐는지는 없음 */
    public record CompletionEntry(UUID participantId, String nickname, boolean completed) {}

    public record Registration(int round, Instant closesAt, Instant closedAt, Progress progress,
                               List<CompletionEntry> completion, List<VenueStateReader.CategorySetting> categories,
                               int uniqueCandidateCount, boolean myCompleted,
                               List<VenueStateReader.Place> myNominations,
                               List<VenueStateReader.CategoryCount> categoryCounts) {}

    public record Voting(int round, Instant closesAt, Progress progress, List<CompletionEntry> completion,
                         List<VenueStateReader.BallotCandidate> candidates, VenueStateReader.Draft myDraft) {}

    public record VoteReview(int round, Progress progress, List<CompletionEntry> completion,
                             boolean canExtend, boolean canProceed) {}

    public record TieDecision(int round, long resultRevision, List<TieGroup> groups) {}

    public record TieGroup(VenueStateReader.Category category, List<VenueStateReader.ResultPlace> places) {}

    public record FinalResult(int round, long resultRevision, SelectedMeeting meeting, int submittedCount,
                              List<VenueStateReader.ResultPlace> selected, List<VenueStateReader.ResultPlace> details,
                              List<UUID> visitOrder) {}
}
