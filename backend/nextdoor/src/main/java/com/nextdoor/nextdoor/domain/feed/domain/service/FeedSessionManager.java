package com.nextdoor.nextdoor.domain.feed.domain.service;

import com.nextdoor.nextdoor.domain.feed.application.port.FeedSessionStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class FeedSessionManager {

    private final FeedSessionStore sessionStore;
    private final FeedGenerator feedGenerator;
    private final Duration sessionTtl;

    public static final int PAGE_SIZE_PUBLIC = 10;

    public FeedSessionManager(
            FeedSessionStore sessionStore,
            FeedGenerator feedGenerator,
            @Value("${feed.session.ttl-seconds:1200}") long ttlSeconds
    ) {
        this.sessionStore = sessionStore;
        this.feedGenerator = feedGenerator;
        this.sessionTtl = Duration.ofSeconds(ttlSeconds);
    }

    /**
     * 페이지에 해당하는 ID 목록 반환
     */
    public List<Long> getIdsForPage(Long memberId, int page, Double lat, Double lon) {
        if (!sessionStore.hasValidSession(memberId)) {
            List<Long> newFeed = feedGenerator.generate(memberId, lat, lon);
            sessionStore.saveSession(memberId, newFeed, sessionTtl);
        }

        // 세션 TTL 슬라이딩
        sessionStore.touchSession(memberId, sessionTtl);

        return sessionStore.getSessionPage(memberId, page, PAGE_SIZE_PUBLIC);
    }
}
