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
        String dataKey = ensureSession(memberId, point);

        sessionStore.touchSession(dataKey, feedConfig.sessionTtl());

        long start = offset;
        long end = offset + windowSize - 1;
        return sessionStore.getSessionRange(dataKey, start, end);
    }

    public void pruneFromSession(Long memberId, Collection<Long> invalidIds) {
        if (invalidIds == null || invalidIds.isEmpty()) return;
        sessionStore.removeFromSession(memberId, invalidIds);
    }

    /**
     * 세션이 유효하면 dataKey를 반환하고, 없거나 만료되었으면 새로 생성 후 dataKey를 반환한다.
     */
    private String ensureSession(Long memberId, GeoPoint point) {
        Optional<String> existing = sessionStore.resolveValidDataKey(memberId, feedConfig.maxSessionTtl());
        if (existing.isPresent()) return existing.get();

        List<Long> candidateIds = geoIndexPort.findNearbyPostIds(
                point, feedConfig.searchRadiusKm(), feedConfig.geoLimit()
        );

        Map<Long, PostMetadata> metadataMap = metadataService.getPostMetadata(candidateIds);
        Map<Category, Long> interests = userInterestPort.getUserInterests(memberId);

        List<Long> sessionIds = feedGenerator.generateSessionIds(
                candidateIds, metadataMap, interests, feedConfig.sessionSize()
        );

        sessionStore.saveSession(memberId, sessionIds, feedConfig.sessionTtl());

        // 새로 생성한 세션의 dataKey를 resolve
        return sessionStore.resolveValidDataKey(memberId, feedConfig.maxSessionTtl())
                .orElseThrow(() -> new IllegalStateException("세션 저장 직후 조회 실패"));
    }
}
