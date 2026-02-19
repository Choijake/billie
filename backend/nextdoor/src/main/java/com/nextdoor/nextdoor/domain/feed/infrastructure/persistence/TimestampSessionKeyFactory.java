package com.nextdoor.nextdoor.domain.feed.infrastructure.persistence;

import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class TimestampSessionKeyFactory implements FeedSessionKeyFactory {

    private final Clock clock;

    public TimestampSessionKeyFactory(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String pointerKey(Long memberId) {
        return "session:ptr:" + memberId;
    }

    @Override
    public String dataKey(Long memberId) {
        return "session:data:" + memberId + ":" + clock.millis();
    }
}