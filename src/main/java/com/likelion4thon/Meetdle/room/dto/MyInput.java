package com.likelion4thon.Meetdle.room.dto;

import com.likelion4thon.Meetdle.contract.SelectedMeeting.Station;

import java.time.Instant;
import java.util.List;

/**
 * 명세 공통 타입 MyInput. 본인에게만 보여준다.
 * 예: {"originStation":{"stationId":"...","name":"강남","lines":["2"],"lat":37.49,"lng":127.02},
 *      "destinationStation":null,"slots":[{"date":"2026-11-20","hour":19}],"inputRevision":4,"completedAt":null}
 */
public record MyInput(
        Station originStation,
        Station destinationStation,
        List<HourSlot> slots,
        long inputRevision,
        Instant completedAt
) {}
