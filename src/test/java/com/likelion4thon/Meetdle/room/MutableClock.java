package com.likelion4thon.Meetdle.room;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 테스트용 시계. "지금"을 고정해 두고, 필요하면 앞으로 감을 수 있다. 예: advance(11분) → 증표 만료 검사 */
public class MutableClock extends Clock {

    private volatile Instant now;

    public MutableClock(Instant start) {
        this.now = start;
    }

    public void set(Instant instant) {
        this.now = instant;
    }

    public void advance(Duration duration) {
        this.now = now.plus(duration);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        // LocalDate.now(clock.withZone(서울)) 처럼 쓸 수 있도록, 같은 순간을 다른 시간대로 보여준다.
        MutableClock self = this;
        return new Clock() {
            @Override
            public Instant instant() {
                return self.instant();
            }

            @Override
            public ZoneId getZone() {
                return zone;
            }

            @Override
            public Clock withZone(ZoneId other) {
                return self.withZone(other);
            }
        };
    }
}
