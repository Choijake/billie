package com.nextdoor.nextdoor.domain.feed.infrastructure.session;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ClockSessionIdGenerator implements SessionIdGenerator {

    private final Clock clock;
    private final AtomicLong seq = new AtomicLong(0);
    private volatile long lastMillis = -1;

    public ClockSessionIdGenerator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized String nextId() {
        long now = clock.millis();
        if (now != lastMillis) {
            lastMillis = now;
            seq.set(0);
        }
        return now + "-" + seq.getAndIncrement();
    }
}