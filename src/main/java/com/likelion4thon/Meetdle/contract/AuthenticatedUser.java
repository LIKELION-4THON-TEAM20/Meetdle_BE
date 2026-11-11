package com.likelion4thon.Meetdle.contract;

import java.util.Objects;
import java.util.UUID;

public record AuthenticatedUser(UUID userId, String nickname) {
    public AuthenticatedUser {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(nickname, "nickname");
    }
}
