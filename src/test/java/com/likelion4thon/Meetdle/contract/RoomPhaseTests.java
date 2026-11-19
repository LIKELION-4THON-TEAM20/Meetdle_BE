package com.likelion4thon.Meetdle.contract;

import static com.likelion4thon.Meetdle.contract.RoomPhase.ENDED;
import static com.likelion4thon.Meetdle.contract.RoomPhase.FINAL_PLACE_CONFIRMED;
import static com.likelion4thon.Meetdle.contract.RoomPhase.RESULT_READY;
import static com.likelion4thon.Meetdle.contract.RoomPhase.PLACE_SUBMISSION_CLOSED;
import static com.likelion4thon.Meetdle.contract.RoomPhase.PLACE_SUBMISSION_OPEN;
import static com.likelion4thon.Meetdle.contract.RoomPhase.RECOVERY_REQUIRED;
import static com.likelion4thon.Meetdle.contract.RoomPhase.VOTE_REVIEW_REQUIRED;
import static com.likelion4thon.Meetdle.contract.RoomPhase.VOTING_OPEN;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RoomPhaseTests {
    @Test
    void registrationClosesBeforeItCanExpireOrReopen() {
        assertTrue(PLACE_SUBMISSION_OPEN.canTransitionTo(PLACE_SUBMISSION_CLOSED));
        assertTrue(PLACE_SUBMISSION_OPEN.canTransitionTo(RESULT_READY));
        assertFalse(PLACE_SUBMISSION_OPEN.canTransitionTo(FINAL_PLACE_CONFIRMED));
        assertFalse(PLACE_SUBMISSION_OPEN.canTransitionTo(VOTING_OPEN));
        assertFalse(PLACE_SUBMISSION_OPEN.canTransitionTo(ENDED));
        assertTrue(PLACE_SUBMISSION_CLOSED.canTransitionTo(ENDED));
        assertTrue(PLACE_SUBMISSION_CLOSED.canTransitionTo(PLACE_SUBMISSION_OPEN));
        assertTrue(PLACE_SUBMISSION_CLOSED.canTransitionTo(VOTING_OPEN));
    }

    @Test
    void reviewCanResumeVotingAndRecoveryStartsAtRegistration() {
        assertTrue(VOTING_OPEN.canTransitionTo(VOTE_REVIEW_REQUIRED));
        assertTrue(VOTE_REVIEW_REQUIRED.canTransitionTo(VOTING_OPEN));
        assertTrue(VOTE_REVIEW_REQUIRED.canTransitionTo(RESULT_READY));
        assertTrue(RESULT_READY.canTransitionTo(FINAL_PLACE_CONFIRMED));
        assertTrue(VOTE_REVIEW_REQUIRED.canTransitionTo(ENDED));
        assertTrue(RECOVERY_REQUIRED.canTransitionTo(PLACE_SUBMISSION_OPEN));
        assertFalse(RECOVERY_REQUIRED.canTransitionTo(VOTING_OPEN));
    }
}
