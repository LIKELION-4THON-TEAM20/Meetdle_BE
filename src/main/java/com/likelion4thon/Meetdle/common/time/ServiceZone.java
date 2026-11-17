package com.likelion4thon.Meetdle.common.time;

import java.time.ZoneId;

/**
 * 시간대 상수. 서버 시계(Clock)는 MeetdleApplication의 serviceClock 빈을 쓴다.
 * 시각은 UTC로 다루고, 약속 날짜(LocalDate)는 서울 기준으로 판단한다.
 */
public final class ServiceZone {

    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private ServiceZone() {}
}
