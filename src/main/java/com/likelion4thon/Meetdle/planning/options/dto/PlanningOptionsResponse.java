package com.likelion4thon.Meetdle.planning.options.dto;

import com.likelion4thon.Meetdle.contract.SelectedMeeting.Station;
import com.likelion4thon.Meetdle.planning.PlanningStateReader;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 명세 PlanningOptions 응답 모양.
 * 창환님 contract(PlanningStateReader.PlanningOptions)를 그대로 쓰면 SlotOption.endsAt이 JSON에 안 나와서
 * (record 필드가 아니라 메서드라서) 응답용으로 한 번 옮겨 담는다.
 */
public record PlanningOptionsResponse(UUID optionsId, long inputVersion, String matrixVersion,
                                      List<SlotOption> slots, List<RegionCandidate> regions) {

    public record SlotOption(UUID slotId, Instant meetingAt, Instant endsAt, int availableCount,
                             List<UUID> unavailableParticipantIds) {}

    public record RegionCandidate(UUID candidateId, Station station, double meanMinutes, double maxMinutes,
                                  double meanTransfers, List<PlanningStateReader.Reason> reasons,
                                  PlanningStateReader.Score score, List<PlanningStateReader.Travel> travel) {}

    public static PlanningOptionsResponse from(PlanningStateReader.PlanningOptions options) {
        return new PlanningOptionsResponse(
                options.optionsId(),
                options.inputVersion(),
                options.matrixVersion(),
                options.slots().stream()
                        .map(s -> new SlotOption(s.slotId(), s.meetingAt(), s.endsAt(), s.availableCount(),
                                s.unavailableParticipantIds()))
                        .toList(),
                options.regions().stream()
                        .map(r -> new RegionCandidate(r.candidateId(), r.station(), r.meanMinutes(),
                                r.maxMinutes(), r.meanTransfers(), r.reasons(), r.score(), r.travel()))
                        .toList());
    }
}
