package com.likelion4thon.Meetdle.contract;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public interface CommandDedup {
    /**
     * Called after authentication and resource access checks. Replays the matching success,
     * rejects a reused key with a different hash, or executes and saves a success atomically.
     */
    Response execute(Key key, String requestHash, Supplier<Response> command);

    record Key(UUID userId, String requestPath, UUID idempotencyKey) {}

    record Response(int statusCode, Optional<URI> location, Optional<String> jsonBody) {}
}
