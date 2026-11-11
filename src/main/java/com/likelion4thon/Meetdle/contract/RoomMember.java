package com.likelion4thon.Meetdle.contract;

import java.util.Objects;
import java.util.UUID;

public record RoomMember(UUID participantId, Role role, DecisionStatus decisionStatus) {
    public RoomMember {
        Objects.requireNonNull(participantId, "participantId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(decisionStatus, "decisionStatus");
    }

    public enum Role {
        HOST, MEMBER
    }

    public enum DecisionStatus {
        PENDING, DECIDER, OBSERVER
    }
}
