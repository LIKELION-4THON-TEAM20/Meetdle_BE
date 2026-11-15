package com.likelion4thon.Meetdle.contract;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VenueStateReader {
    /** Projects venue-owned state in the caller's read transaction. */
    VenueState project(ViewContext context);

    record ViewContext(UUID roomId, RoomMember viewer, RoomPhase phase, Optional<Instant> meetingAt, Instant now) {}

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
            List<CategoryCount> categoryCounts
    ) {
        public Registration {
            categories = List.copyOf(categories);
            completedParticipantIds = List.copyOf(completedParticipantIds);
            myNominations = List.copyOf(myNominations);
            if (categoryCounts != null) {
                categoryCounts = List.copyOf(categoryCounts);
            }
        }
    }

    record CategorySetting(Category category, boolean required, boolean available, int radiusMeters) {}

    record CategoryCount(Category category, int count) {}

    record Voting(int round, Instant closesAt, List<UUID> completedParticipantIds,
                  List<BallotCandidate> candidates, Draft myDraft) {
        public Voting {
            completedParticipantIds = List.copyOf(completedParticipantIds);
            candidates = List.copyOf(candidates);
        }
    }

    /** Decision flags are true only for a host permitted to take the respective action. */
    record VoteReview(int round, List<UUID> completedParticipantIds, boolean canExtend, boolean canProceed) {
        public VoteReview {
            completedParticipantIds = List.copyOf(completedParticipantIds);
        }
    }

    record Draft(long draftRevision, List<Selection> selections, boolean submitted) {
        public Draft {
            selections = List.copyOf(selections);
        }
    }

    record Place(String name, String address, double lat, double lng, Category category, URI detailUrl) {}

    record BallotCandidate(UUID candidateId, Place place, BallotMode mode) {}

    record Selection(UUID candidateId, Choice choice) {}

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
            List<UUID> visitOrder
    ) {
        public FinalResult {
            selected = List.copyOf(selected);
            details = List.copyOf(details);
            visitOrder = List.copyOf(visitOrder);
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
        NO_ELIGIBLE_RESULT, INSUFFICIENT_TIME, RETRY_EXHAUSTED
    }

    enum Action {
        OPEN_COLLECTION, SEARCH_PLACES, EDIT_NOMINATION, COMPLETE_COLLECTION, CLOSE_COLLECTION,
        START_MINI_TIMER, EXTEND_COLLECTION, START_VOTING, EDIT_PREFERENCES, SUBMIT_VOTE,
        EXTEND_VOTE, PROCEED_WITH_VOTES, RECOLLECT, RESOLVE_TIES, REORDER_VISIT
    }
}
