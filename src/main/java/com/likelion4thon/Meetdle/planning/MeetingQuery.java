package com.likelion4thon.Meetdle.planning;

import com.likelion4thon.Meetdle.contract.SelectedMeeting;

import java.util.Optional;
import java.util.UUID;

/** Reads the room's current confirmed selection in the caller's transaction. */
public interface MeetingQuery {
    Optional<SelectedMeeting> find(UUID roomId);
}
