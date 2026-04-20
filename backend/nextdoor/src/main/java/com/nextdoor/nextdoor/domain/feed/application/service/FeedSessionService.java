package com.nextdoor.nextdoor.domain.feed.application.service;

import com.nextdoor.nextdoor.domain.feed.application.FeedConfig;
import com.nextdoor.nextdoor.domain.feed.application.port.FeedGeoIndexPort;
import com.nextdoor.nextdoor.domain.feed.application.port.FeedSessionStore;
import com.nextdoor.nextdoor.domain.feed.application.port.UserInterestPort;
import com.nextdoor.nextdoor.domain.feed.application.service.dto.PostMetadata;
import com.nextdoor.nextdoor.domain.feed.domain.GeoPoint;
import com.nextdoor.nextdoor.domain.feed.domain.service.FeedGenerator;
import com.nextdoor.nextdoor.domain.post.domain.Category;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class FeedSessionService {

    private final FeedSessionStore sessionStore;
    private final FeedGeoIndexPort geoIndexPort;
    private final UserInterestPort userInterestPort;
    private final PostMetadataService metadataService;

    private final FeedGenerator feedGenerator;

    private final FeedConfig feedConfig;

    public List<Long> getIdsForWindow(Long memberId, long offset, int windowSize, GeoPoint point) {
        ensureSession(memberId, point);
        sessionStore.touchSession(memberId, feedConfig.sessionTtl());

        long start = offset;
        long end = offset + windowSize - 1;
        return sessionStore.getSessionRange(memberId, start, end);
    }

    public void pruneFromSession(Long memberId, Collection<Long> invalidIds) {
        if (invalidIds == null || invalidIds.isEmpty()) return;
        sessionStore.removeFromSession(memberId, invalidIds);
    }

    // 세션 생성
    private void ensureSession(Long memberId, GeoPoint point) {
        if (sessionStore.hasValidSession(memberId)) return;

        List<Long> candidateIds = geoIndexPort.findNearbyPostIds(
                point, feedConfig.searchRadiusKm(), feedConfig.geoLimit()
        );

        Map<Long, PostMetadata> metadataMap = metadataService.getPostMetadata(candidateIds);
        Map<Category, Long> interests = userInterestPort.getUserInterests(memberId);

        long shuffleSeed = memberId ^ System.currentTimeMillis();
        List<Long> sessionIds = feedGenerator.generateSessionIds(
                candidateIds, metadataMap, interests, feedConfig.sessionSize(), shuffleSeed
        );

        sessionStore.saveSession(memberId, sessionIds, feedConfig.sessionTtl());
    }
}
