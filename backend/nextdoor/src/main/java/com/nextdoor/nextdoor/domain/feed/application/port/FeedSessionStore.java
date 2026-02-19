package com.nextdoor.nextdoor.domain.feed.application.port;

import java.time.Duration;
import java.util.List;

public interface FeedSessionStore {
    boolean hasValidSession(Long memberId);
    void saveSession(Long memberId, List<Long> postIds, Duration ttl);
    void touchSession(Long memberId, Duration ttl);
    List<Long> getSessionPage(Long memberId, int page, int size);
}