package com.likelion4thon.Meetdle.planning.options;

import com.likelion4thon.Meetdle.contract.SelectedMeeting;
import com.likelion4thon.Meetdle.planning.PlanningStateReader;
import com.likelion4thon.Meetdle.planning.station.StationRepository;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 창환님 PlanningStateReader 계약 구현. A08 방 상태 조립할 때 쓴다. */
@Component
public class PlanningStateQuery implements PlanningStateReader {

    private final PlanningOptionsRepository optionsRepository;
    private final StationRepository stationRepository;

    public PlanningStateQuery(PlanningOptionsRepository optionsRepository, StationRepository stationRepository) {
        this.optionsRepository = optionsRepository;
        this.stationRepository = stationRepository;
    }

    @Override
    public Optional<PlanningOptions> findOptions(UUID roomId) {
        return optionsRepository.find(roomId);
    }

    @Override
    public Map<UUID, SelectedMeeting.Station> findStations(Set<UUID> stationIds) {
        return stationRepository.findByIds(stationIds).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().station()));
    }
}
