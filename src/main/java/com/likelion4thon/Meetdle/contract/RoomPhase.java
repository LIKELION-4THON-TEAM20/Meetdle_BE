package com.likelion4thon.Meetdle.contract;

public enum RoomPhase {
    WAITING_FOR_DEPARTURE,
    OPTIONS_READY,
    REGION_CONFIRMED,
    PLACE_SUBMISSION_OPEN,
    PLACE_SUBMISSION_CLOSED,
    VOTING_OPEN,
    WAITING_FOR_HOST_DECISION,
    FINAL_PLACE_CONFIRMED,
    RECOVERY_REQUIRED,
    ENDED;

    /**
     * Checks phase transitions only. The command handler checks actor, timing, and other business rules.
     */
    public boolean canTransitionTo(RoomPhase next) {
        return switch (this) {
            case WAITING_FOR_DEPARTURE -> next == OPTIONS_READY;
            case OPTIONS_READY -> next == REGION_CONFIRMED;
            case REGION_CONFIRMED -> next == PLACE_SUBMISSION_OPEN;
            case PLACE_SUBMISSION_OPEN -> next == PLACE_SUBMISSION_CLOSED
                    || next == VOTING_OPEN || next == FINAL_PLACE_CONFIRMED;
            case PLACE_SUBMISSION_CLOSED -> next == PLACE_SUBMISSION_OPEN
                    || next == VOTING_OPEN || next == FINAL_PLACE_CONFIRMED || next == ENDED;
            case VOTING_OPEN -> next == WAITING_FOR_HOST_DECISION
                    || next == FINAL_PLACE_CONFIRMED || next == RECOVERY_REQUIRED || next == ENDED;
            case WAITING_FOR_HOST_DECISION -> next == FINAL_PLACE_CONFIRMED || next == ENDED;
            case RECOVERY_REQUIRED -> next == PLACE_SUBMISSION_OPEN || next == VOTING_OPEN || next == ENDED;
            case FINAL_PLACE_CONFIRMED, ENDED -> false;
        };
    }
}
