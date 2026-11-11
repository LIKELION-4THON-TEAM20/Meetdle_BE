package com.likelion4thon.Meetdle.contract;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VenueStateReader {
    /** Returns only the venue state fields visible to the verified room member. */
    VenueState project(UUID roomId, RoomMember viewer, RoomPhase phase, Optional<Instant> meetingAt, Instant now);

    /**
     * Absent phase-specific fields are null. The list of permitted venue actions is always present.
     */
    record VenueState(
            Integer round,
            Instant voteClosedAt,
            CollectionState collection,
            Voting voting,
            TieDecision tieDecision,
            FinalResult result,
            Recovery recovery,
            List<Action> allowedActions
    ) {
        public VenueState {
            allowedActions = List.copyOf(allowedActions);
        }
    }

    record CollectionState(
            Instant closesAt,
            List<Category> activeCategories,
            int uniqueCandidateCount,
            List<UUID> completedParticipantIds,
            List<Place> myNominations,
            List<CategoryCount> categoryCounts,
            boolean extensionUsed
    ) {
        public CollectionState {
            activeCategories = List.copyOf(activeCategories);
            completedParticipantIds = List.copyOf(completedParticipantIds);
            myNominations = List.copyOf(myNominations);
            if (categoryCounts != null) {
                categoryCounts = List.copyOf(categoryCounts);
            }
        }
    }

    record CategoryCount(Category category, int count) {}

    record Voting(Instant closesAt, List<UUID> completedParticipantIds, List<BallotCandidate> candidates, Draft myDraft) {
        public Voting {
            completedParticipantIds = List.copyOf(completedParticipantIds);
            candidates = List.copyOf(candidates);
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

    record Aggregate(int like, int neutral, int dislike, int score) {}

    record ResultPlace(UUID candidateId, Place place, ResultStatus status, Aggregate aggregate) {}

    record TieGroup(Category category, List<ResultPlace> candidates) {
        public TieGroup {
            candidates = List.copyOf(candidates);
        }
    }

    record TieDecision(long resultRevision, List<TieGroup> groups) {
        public TieDecision {
            groups = List.copyOf(groups);
        }
    }

    /** Venue result fields for the room state response. */
    record FinalResult(
            long resultRevision,
            int submittedCount,
            List<ResultPlace> selected,
            List<ResultPlace> details,
            List<UUID> visitOrder
    ) {
        public FinalResult {
            selected = List.copyOf(selected);
            details = List.copyOf(details);
            if (visitOrder != null) {
                visitOrder = List.copyOf(visitOrder);
            }
        }
    }

    record Recovery(RecoveryReason reason, RecoveryMode mode) {}

    enum Category {
        FOOD, CAFE, BAR, CULTURE
    }

    enum BallotMode {
        VOTE, AUTO_SELECTED
    }

    enum ResultStatus {
        SELECTED, RANKED, EXCLUDED, AUTO_SELECTED
    }

    enum Choice {
        LIKE, NEUTRAL, DISLIKE
    }

    enum RecoveryMode {
        REVOTE, RECOLLECT
    }

    enum RecoveryReason {
        LOW_TURNOUT, ALL_REJECTED, INSUFFICIENT_TIME, RETRY_EXHAUSTED, HOST_DECISION_EXPIRED
    }

    enum Action {
        OPEN_COLLECTION, SEARCH_PLACES, EDIT_NOMINATION, COMPLETE_COLLECTION, START_VOTING,
        EXTEND_COLLECTION, EDIT_PREFERENCES, SUBMIT_VOTE,
        RECOVER, RESOLVE_TIES, REORDER_VISIT
    }
}
