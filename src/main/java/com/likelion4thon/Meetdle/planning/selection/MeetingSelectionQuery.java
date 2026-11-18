package com.likelion4thon.Meetdle.planning.selection;

import com.likelion4thon.Meetdle.contract.SelectedMeeting;
import com.likelion4thon.Meetdle.planning.MeetingQuery;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** 창환님 MeetingQuery 계약 구현. 장소(venue) 쪽이 "지금 확정된 역·시각"을 읽을 때 쓴다. */
@Component
public class MeetingSelectionQuery implements MeetingQuery {

    private final MeetingSelectionRepository selectionRepository;

    public MeetingSelectionQuery(MeetingSelectionRepository selectionRepository) {
        this.selectionRepository = selectionRepository;
    }

    @Override
    public Optional<SelectedMeeting> find(UUID roomId) {
        return selectionRepository.findCurrent(roomId).map(MeetingSelectionRepository.StoredSelection::meeting);
    }
}
