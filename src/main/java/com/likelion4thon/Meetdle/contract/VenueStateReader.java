package com.likelion4thon.Meetdle.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VenueStateReader {
    /** Projects venue-owned state in the caller's read transaction. */
    VenueState project(ViewContext context);

    /** Verified room state from the same read transaction as the venue projection. */
    record ViewContext(UUID roomId, RoomMember viewer, RoomPhase phase, int capacity,
                       Optional<Instant> meetingAt, Instant now) {}

    /**
     * Absent phase-specific fields are null. The list of permitted venue actions is always present.
     */
    record VenueState(
            Registration registration,
            Voting voting,
            VoteReview voteReview,
            TieDecision tieDecision,
            FinalResult result,
            Recovery recovery,
            List<Action> allowedActions
    ) {
        public VenueState {
            allowedActions = List.copyOf(allowedActions);
        }
    }

    record Registration(
            int round,
            Instant closesAt,
            Instant closedAt,
            List<CategorySetting> categories,
            int uniqueCandidateCount,
            List<UUID> completedParticipantIds,
            List<Place> myNominations,
            List<CategoryCount> categoryCounts,
            List<UUID> startedParticipantIds,
            List<Nomination> myCandidates
    ) {
        public Registration {
            categories = List.copyOf(categories);
            completedParticipantIds = List.copyOf(completedParticipantIds);
            myNominations = List.copyOf(myNominations);
            startedParticipantIds = List.copyOf(startedParticipantIds);
            myCandidates = List.copyOf(myCandidates);
            if (categoryCounts != null) {
                categoryCounts = List.copyOf(categoryCounts);
            }
        }

        public Registration(int round, Instant closesAt, Instant closedAt, List<CategorySetting> categories,
                int uniqueCandidateCount, List<UUID> completedParticipantIds, List<Place> myNominations,
                List<CategoryCount> categoryCounts) {
            this(round, closesAt, closedAt, categories, uniqueCandidateCount, completedParticipantIds,
                    myNominations, categoryCounts, List.of(), List.of());
        }
    }

    record Nomination(UUID candidateId, Place place) {}

    record CategorySetting(Category category, boolean required, boolean available, int radiusMeters) {}

    record CategoryCount(Category category, int count) {}

    record Voting(int round, Instant closesAt, List<UUID> completedParticipantIds,
                  List<BallotCandidate> candidates, Draft myDraft, List<UUID> startedParticipantIds) {
        public Voting {
            completedParticipantIds = List.copyOf(completedParticipantIds);
            candidates = List.copyOf(candidates);
            startedParticipantIds = List.copyOf(startedParticipantIds);
        }

        public Voting(int round, Instant closesAt, List<UUID> completedParticipantIds,
                       List<BallotCandidate> candidates, Draft myDraft) {
            this(round, closesAt, completedParticipantIds, candidates, myDraft, List.of());
        }
    }

    /** Decision flags are true only for a host permitted to take the respective action. */
    record VoteReview(int round, List<UUID> completedParticipantIds, boolean canExtend, boolean canProceed,
                      List<UUID> startedParticipantIds) {
        public VoteReview {
            completedParticipantIds = List.copyOf(completedParticipantIds);
            startedParticipantIds = List.copyOf(startedParticipantIds);
        }
        public VoteReview(int round, List<UUID> completedParticipantIds, boolean canExtend, boolean canProceed) {
            this(round, completedParticipantIds, canExtend, canProceed, List.of());
        }
    }

    record Draft(long draftRevision, List<Selection> selections, boolean submitted) {
        public Draft {
            selections = List.copyOf(selections);
        }
    }

    record Place(String name, String address, double lat, double lng, Category category, URI detailUrl, URI imageUrl) {
        public Place(String name, String address, double lat, double lng, Category category, URI detailUrl) {
            this(name, address, lat, lng, category, detailUrl, null);
        }
    }

    record BallotCandidate(UUID candidateId, Place place, BallotMode mode) {}

    record Selection(
            @Schema(description = "방 상태의 voting.candidates에서 mode가 VOTE인 후보 UUID")
            UUID candidateId,
            @Schema(description = "후보 선호도. LIKE는 선호, NEUTRAL은 보통, DISLIKE는 비선호")
            Choice choice
    ) {}

    record Aggregate(int like, int neutral, int dislike) {}

    record ResultPlace(UUID candidateId, Place place, Aggregate aggregate) {}

    record TieGroup(Category category, List<ResultPlace> candidates) {
        public TieGroup {
            candidates = List.copyOf(candidates);
        }
    }

    record TieDecision(int round, long resultRevision, List<TieGroup> groups) {
        public TieDecision {
            groups = List.copyOf(groups);
        }
    }

    /** Venue result fields for the room state response. */
    record FinalResult(
            int round,
            long resultRevision,
            int submittedCount,
            List<ResultPlace> selected,
            List<ResultPlace> details,
            List<UUID> visitOrder,
            boolean confirmed
    ) {
        public FinalResult {
            selected = List.copyOf(selected);
            details = List.copyOf(details);
            visitOrder = List.copyOf(visitOrder);
        }

        public FinalResult(int round, long resultRevision, int submittedCount, List<ResultPlace> selected,
                           List<ResultPlace> details, List<UUID> visitOrder) {
            this(round, resultRevision, submittedCount, selected, details, visitOrder, true);
        }
    }

    record Recovery(int round, RecoveryReason reason) {}

    enum Category {
        FOOD, CAFE, CULTURE, BAR
    }

    enum BallotMode {
        VOTE, AUTO_SELECTED
    }

    enum Choice {
        LIKE, NEUTRAL, DISLIKE
    }

    enum RecoveryReason {
        NO_ELIGIBLE_RESULT, INSUFFICIENT_TIME, RETRY_EXHAUSTED, NO_SUBMITTED_VOTES,
        DECISION_EXPIRED, REGISTRATION_EXPIRED, HOST_DECISION_EXPIRED, CONFIRMATION_EXPIRED
    }

    enum Action {
        OPEN_COLLECTION, SEARCH_PLACES, EDIT_NOMINATION, COMPLETE_COLLECTION, CLOSE_COLLECTION,
        START_MINI_TIMER, EXTEND_COLLECTION, START_VOTING, EDIT_PREFERENCES, SUBMIT_VOTE,
        EXTEND_VOTE, PROCEED_WITH_VOTES, RECOLLECT, RESOLVE_TIES, REORDER_VISIT, CONFIRM_RESULT
    }
}
