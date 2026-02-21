package com.nextdoor.nextdoor.domain.feed.infrastructure.session;

import org.springframework.stereotype.Component;

@Component
public class TimestampSessionKeyFactory implements FeedSessionKeyFactory {

    private final SessionIdGenerator idGenerator;

    public TimestampSessionKeyFactory(SessionIdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    @Override
    public String pointerKey(Long memberId) {
        return "session:ptr:" + memberId;
    }

    @Override
    public String dataKey(Long memberId) {
        return "session:data:" + memberId + ":" + idGenerator.nextId();
    }
}