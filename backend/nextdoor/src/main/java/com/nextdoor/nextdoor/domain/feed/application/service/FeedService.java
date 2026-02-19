package com.nextdoor.nextdoor.domain.feed.application.service;

import com.nextdoor.nextdoor.domain.feed.application.service.dto.FeedItemDto;
import com.nextdoor.nextdoor.domain.feed.application.service.dto.PostMetadata;
import com.nextdoor.nextdoor.domain.feed.domain.Exception.FeedRedisUnavailableException;
import com.nextdoor.nextdoor.domain.feed.domain.service.EsFallbackFeedService;
import com.nextdoor.nextdoor.domain.feed.domain.service.FeedSessionManager;
import com.nextdoor.nextdoor.domain.feed.infrastructure.persistence.FeedCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedService {

    private final FeedSessionManager sessionManager;
    private final FeedCacheRepository feedRepository;
    private final EsFallbackFeedService esFallbackFeedService;

    public List<FeedItemDto> getHomeFeed(Long memberId, Double lat, Double lon, int page) {
        if (lat == null || lon == null) return Collections.emptyList();

        try {
            List<Long> targetIds = sessionManager.getIdsForPage(memberId, page, lat, lon);

            if (targetIds.isEmpty()) {
                return Collections.emptyList();
            }

            Map<Long, PostMetadata> metadataMap = feedRepository.getPostMetadata(targetIds);

            return targetIds.stream()
                    .map(metadataMap::get)
                    .filter(Objects::nonNull)
                    .map(FeedItemDto::from)
                    .collect(Collectors.toList());

        } catch (FeedRedisUnavailableException e) {
            log.warn("[Feed Redis Unavailable → ES Fallback] memberId={}, page={}, err={}",
                    memberId, page, e.getMessage());

            return esFallbackFeedService.getHomeFeed(
                    memberId,
                    lat,
                    lon,
                    page,
                    FeedSessionManager.PAGE_SIZE_PUBLIC
            );

        } catch (Exception e) {
            log.error("[Feed Unexpected Error → ES Fallback] memberId={}, page={}, err={}",
                    memberId, page, e.toString());

            return esFallbackFeedService.getHomeFeed(
                    memberId,
                    lat,
                    lon,
                    page,
                    FeedSessionManager.PAGE_SIZE_PUBLIC
            );
        }
    }

    public void addGeoLocation(Long postId, Double lat, Double lon) {
        feedRepository.addGeoLocation(postId, lat, lon);
    }
}