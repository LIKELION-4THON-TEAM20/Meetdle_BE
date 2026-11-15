package com.likelion4thon.Meetdle.contract;

import java.util.Objects;
import java.util.UUID;

public record RoomMember(UUID participantId, Role role) {
    public RoomMember {
        Objects.requireNonNull(participantId, "participantId");
        Objects.requireNonNull(role, "role");
    }

    public enum Role {
        HOST, MEMBER
    }
}
