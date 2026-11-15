package com.likelion4thon.Meetdle.room;

import com.likelion4thon.Meetdle.contract.RoomMember;
import com.likelion4thon.Meetdle.contract.RoomPhase;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reads room state after verifying the viewer's membership. */
public interface RoomSummaryReader {
    RoomSummary read(UUID roomId, UUID userId);

    record RoomSummary(UUID roomId, String title, LocalDate startsOn, LocalDate endsOn, int capacity,
                       RoomPhase phase, long revision, long inputVersion, RoomMember viewer,
                       List<MemberStatus> members, Optional<RoomWorkflow.MemberInput> myInput,
                       Optional<Invite> invite) {
        public RoomSummary {
            members = List.copyOf(members);
        }
    }

    record MemberStatus(RoomMember member, String nickname, boolean inputComplete) {}

    /** Included only for the host. */
    record Invite(String code, URI inviteUrl, Instant expiresAt) {}
}
