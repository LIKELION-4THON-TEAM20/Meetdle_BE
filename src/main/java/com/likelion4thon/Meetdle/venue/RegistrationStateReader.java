package com.likelion4thon.Meetdle.venue;

import com.likelion4thon.Meetdle.contract.MeetingWindow;
import com.likelion4thon.Meetdle.contract.RoomMember.Role;
import com.likelion4thon.Meetdle.contract.VenueStateReader;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

import static com.likelion4thon.Meetdle.contract.RoomPhase.*;

@Component
class RegistrationStateReader implements VenueStateReader {
    private final RegistrationStore store;
    private final PlaceVoting voting;

    private final VoteResults results;

    RegistrationStateReader(RegistrationStore store, PlaceVoting voting, VoteResults results) {
        this.store = store;
        this.voting = voting;
        this.results = results;
    }

    @Override
    public VenueState project(ViewContext context) {
        boolean host = context.viewer().role() == Role.HOST;
        var actions = new ArrayList<Action>();
        var meetingAt = context.meetingAt().orElse(null);
        boolean hasTime = meetingAt != null && !context.now().isAfter(PlaceRegistration.cutoff(meetingAt, context.now()));
        if (context.phase() == REGION_CONFIRMED && host && hasTime) {
            var window = MeetingWindow.calculate(meetingAt, context.now());
            actions.add(Action.SEARCH_PLACES);
            if (!context.now().plus(window.registrationMinimum())
                    .isAfter(PlaceRegistration.cutoff(meetingAt, context.now()))) {
                actions.add(Action.OPEN_COLLECTION);
            }
        }
        if (context.phase() == FINAL_PLACE_CONFIRMED || context.phase() == RESULT_READY) {
            var result = store.latest(context.roomId()).map(results::read).orElse(null);
            if (host && result != null && (context.phase() == FINAL_PLACE_CONFIRMED
                    || (meetingAt != null && context.now().isBefore(meetingAt)))) {
                actions.add(Action.REORDER_VISIT);
                if (context.phase() == RESULT_READY && meetingAt != null && context.now().isBefore(meetingAt)) {
                    actions.add(Action.CONFIRM_RESULT);
                }
            }
            return new VenueState(null, null, null, null, result, null, actions);
        }
        if (context.phase() == WAITING_FOR_HOST_DECISION && host
                && meetingAt != null && context.now().isBefore(meetingAt)) {
            var ties = store.latest(context.roomId()).map(results::ties).orElse(null);
            if (ties != null) {
                actions.add(Action.RESOLVE_TIES);
            }
            var partial = store.latest(context.roomId()).map(results::read).orElse(null);
            if (partial != null && !partial.selected().isEmpty()) {
                actions.add(Action.REORDER_VISIT);
            }
            return new VenueState(null, null, null, ties, partial, null, actions);
        }
        if (context.phase() == VOTE_REVIEW_REQUIRED) {
            return store.latest(context.roomId()).map(round -> {
                var submitted = voting.submitted(round);
                boolean beforeCutoff = meetingAt != null
                        && context.now().isBefore(MeetingWindow.calculate(meetingAt, context.now()).votingCutoff());
                boolean canExtend = host && beforeCutoff && round.voteExtensionCount() == 0;
                boolean canProceed = host && beforeCutoff && !submitted.isEmpty();
                if (canExtend) {
                    actions.add(Action.EXTEND_VOTE);
                }
                if (canProceed) {
                    actions.add(Action.PROCEED_WITH_VOTES);
                }
                return new VenueState(null, null, new VoteReview(round.number(), submitted, canExtend, canProceed,
                        store.started(round, true)),
                        null, null, null, actions);
            }).orElseGet(() -> state(null, actions));
        }
        if (context.phase() == RECOVERY_REQUIRED || context.phase() == ENDED) {
            return store.latest(context.roomId()).filter(round -> round.recoveryReason() != null).map(round -> {
                var reason = RecoveryReason.valueOf(round.recoveryReason());
                if (context.phase() == RECOVERY_REQUIRED && meetingAt != null) {
                    reason = PlaceVoting.recoveryReason(round, meetingAt, context.now());
                    if (host && reason == RecoveryReason.NO_ELIGIBLE_RESULT) {
                        actions.add(Action.RECOLLECT);
                    }
                }
                return new VenueState(null, null, null, null, null, new Recovery(round.number(), reason), actions);
            }).orElseGet(() -> state(null, actions));
        }
        if (context.phase() == VOTING_OPEN) {
            return store.latest(context.roomId()).map(round -> {
                var ballot = voting.project(round, context.viewer().participantId());
                var draft = ballot.myDraft();
                if (draft != null && !draft.submitted() && context.now().isBefore(ballot.closesAt())) {
                    actions.add(Action.EDIT_PREFERENCES);
                    if (draft.selections().size() == ballot.candidates().stream()
                            .filter(candidate -> candidate.mode() == BallotMode.VOTE).count()) {
                        actions.add(Action.SUBMIT_VOTE);
                    }
                }
                return new VenueState(null, ballot, null, null, null, null, actions);
            }).orElseGet(() -> state(null, actions));
        }
        if (context.phase() != PLACE_SUBMISSION_OPEN && context.phase() != PLACE_SUBMISSION_CLOSED) {
            return state(null, actions);
        }
        var round = store.latest(context.roomId()).orElse(null);
        if (round == null) {
            return state(null, actions);
        }
        var participant = context.viewer().participantId();
        var completed = store.completed(round);
        var counts = store.counts(round);
        boolean open = context.phase() == PLACE_SUBMISSION_OPEN && context.now().isBefore(round.closesAt());
        boolean enough = store.hasRequiredCandidates(round, counts);
        List<Place> nominations = store.nominations(round, participant);
        if (open && store.eligible(round, participant)) {
            actions.add(Action.SEARCH_PLACES);
            if (!completed.contains(participant)) {
                actions.add(Action.EDIT_NOMINATION);
                if (!nominations.isEmpty()) {
                    actions.add(Action.COMPLETE_COLLECTION);
                }
            }
        }
        if (host && hasTime) {
            int capacity = context.capacity();
            boolean early = PlaceRegistration.canStartEarly(capacity, completed.size());
            if (enough && context.phase() == PLACE_SUBMISSION_CLOSED) {
                actions.add(Action.START_VOTING);
            }
            if (open && enough && PlaceRegistration.canCloseEarly(capacity, completed.size())) {
                actions.add(Action.CLOSE_COLLECTION);
            }
            var cutoff = PlaceRegistration.cutoff(meetingAt, context.now());
            if (completed.size() < store.eligibleCount(round) || !enough) {
                if (open && completed.size() < store.eligibleCount(round) && early && !round.miniTimerUsed()
                        && cutoff.isAfter(round.closesAt())) {
                    actions.add(Action.START_MINI_TIMER);
                }
                if (context.phase() == PLACE_SUBMISSION_CLOSED && !round.extended() && cutoff.isAfter(context.now())) {
                    actions.add(Action.EXTEND_COLLECTION);
                }
            }
        }
        return state(new Registration(round.number(), round.closesAt(), round.closedAt(), store.categories(round),
                counts.stream().mapToInt(CategoryCount::count).sum(), completed, nominations,
                host && (!open || actions.contains(Action.CLOSE_COLLECTION)) ? counts : null,
                store.started(round, false), store.myCandidates(round, participant)), actions);
    }

    private static VenueState state(Registration registration, List<Action> actions) {
        return new VenueState(registration, null, null, null, null, null, actions);
    }
}
