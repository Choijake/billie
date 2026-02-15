package com.nextdoor.nextdoor.domain.feed.domain.service;

import com.nextdoor.nextdoor.domain.feed.infrastructure.persistence.FeedCacheRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@RequiredArgsConstructor
public class FeedSessionManager {

    private final FeedCacheRepository feedRepository;
    private final FeedGenerator feedGenerator;

    private static final Duration SESSION_TTL = Duration.ofMinutes(20);
    public static final int PAGE_SIZE_PUBLIC = 10;

    /**
     * 페이지에 해당하는 ID 목록 반환
     */
    public List<Long> getIdsForPage(Long memberId, int page, Double lat, Double lon) {
        if (page == 0) {
            if (feedRepository.hasFeedSession(memberId)) {
                return feedRepository.getFeedSessionPage(memberId, page, PAGE_SIZE_PUBLIC);
            }

            List<Long> newFeed = feedGenerator.generate(memberId, lat, lon);
            feedRepository.saveFeedSession(memberId, newFeed, SESSION_TTL);
        }

        return feedRepository.getFeedSessionPage(memberId, page, PAGE_SIZE_PUBLIC);
    }
}