package com.nextdoor.nextdoor.domain.feed.application.port;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FeedSessionStore {

    /**
     * 세션 유효성 검증 + dataKey 반환.
     * 유효하면 dataKey를, 무효하면 empty를 반환한다.
     */
    Optional<String> resolveValidDataKey(Long memberId, Duration maxTtl);

    void saveSession(Long memberId, List<Long> postIds, Duration ttl);
    void touchSession(String dataKey, Duration ttl);
    List<Long> getSessionRange(String dataKey, long start, long endInclusive);
    void removeFromSession(Long memberId, Collection<Long> postIds);
}
