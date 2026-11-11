package com.likelion4thon.Meetdle.contract;

import static com.likelion4thon.Meetdle.contract.RoomPhase.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RoomPhaseTests {
    @Test
    void registrationClosesBeforeItCanExpireOrReopen() {
        assertTrue(PLACE_SUBMISSION_OPEN.canTransitionTo(PLACE_SUBMISSION_CLOSED));
        assertFalse(PLACE_SUBMISSION_OPEN.canTransitionTo(ENDED));
        assertTrue(PLACE_SUBMISSION_CLOSED.canTransitionTo(ENDED));
        assertTrue(PLACE_SUBMISSION_CLOSED.canTransitionTo(PLACE_SUBMISSION_OPEN));
        assertTrue(PLACE_SUBMISSION_CLOSED.canTransitionTo(VOTING_OPEN));
    }
}
