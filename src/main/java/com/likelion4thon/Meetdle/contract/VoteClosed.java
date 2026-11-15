package com.likelion4thon.Meetdle.contract;

import java.time.Instant;
import java.util.UUID;

/** Event recorded when an opened vote is finally closed, once per round. */
public record VoteClosed(UUID roomId, int round, Instant closedAt) {}
