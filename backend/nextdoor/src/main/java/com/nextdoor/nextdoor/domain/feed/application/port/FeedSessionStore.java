package com.nextdoor.nextdoor.domain.feed.application.port;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

public interface FeedSessionStore {
    boolean hasValidSession(Long memberId, Duration maxTtl);
    void saveSession(Long memberId, List<Long> postIds, Duration ttl);
    void touchSession(Long memberId, Duration ttl);
    List<Long> getSessionRange(Long memberId, long start, long endInclusive);
    void removeFromSession(Long memberId, Collection<Long> postIds);
}
