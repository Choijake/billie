package com.nextdoor.nextdoor.domain.feed.application.service;

import com.nextdoor.nextdoor.domain.feed.application.FeedConfig;
import com.nextdoor.nextdoor.domain.feed.application.usecase.GetHomeFeedUseCase;
import com.nextdoor.nextdoor.domain.feed.application.service.dto.FeedItemDto;
import com.nextdoor.nextdoor.domain.feed.application.service.dto.PostMetadata;
import com.nextdoor.nextdoor.domain.feed.domain.GeoPoint;
import com.nextdoor.nextdoor.domain.feed.infrastructure.exception.InfraException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class GetHomeFeedService implements GetHomeFeedUseCase {

    private final FeedSessionService sessionService;
    private final PostMetadataService metadataService;
    private final FeedPageComposer pageComposer;
    private final FeedStaleCache staleCache;
    private final EsFallbackFeedService esFallback;
    private final BestEffortExecutor bestEffortExecutor;

    private final FeedConfig feedConfig;

    @Override
    public List<FeedItemDto> getHomeFeed(Long memberId, Double lat, Double lon, int page) {
        Optional<GeoPoint> pointOpt = GeoPoint.of(lat, lon);
        if (pointOpt.isEmpty()) return Collections.emptyList();

        GeoPoint point = pointOpt.get();

        int size = feedConfig.pageSize();
        long offset = (long) page * size;
        int windowSize = size + feedConfig.backfillExtraWindow();

        try {
            return getFromPrimary(memberId, lat, lon, page, size, offset, windowSize, point);
        } catch (InfraException e) {
            log.warn("[Feed Infra Failure → ES Fallback] memberId={}, page={}, err={}",
                    memberId, page, e.getMessage());
            return resolveWithStaleThenFallback(memberId, lat, lon, page, size);
        }
    }

    private List<FeedItemDto> getFromPrimary(Long memberId, Double lat, Double lon, int page, int size,
                                             long offset, int windowSize, GeoPoint point) {
        // 1 세션 window 조회
        List<Long> windowIds = sessionService.getIdsForWindow(memberId, offset, windowSize, point);
        if (windowIds.isEmpty()) return Collections.emptyList();

        // 2 메타 조회
        Map<Long, PostMetadata> metadataMap = metadataService.getPostMetadata(windowIds);

        // 3 페이지 구성 + 누락 id 수집
        FeedPageComposer.Result result = pageComposer.compose(windowIds, metadataMap, size);

        // 4 누락/삭제 id는 세션에서 제거 (best-effort)
        if (!result.missingIds().isEmpty()) {
            bestEffortExecutor.run("feed.session.prune",
                    () -> sessionService.pruneFromSession(memberId, result.missingIds()));
        }

        List<FeedItemDto> items = result.items();
        staleCache.putFromPrimary(memberId, lat, lon, page, size, items);
        return items;
    }

    private List<FeedItemDto> resolveWithStaleThenFallback(Long memberId, Double lat, Double lon, int page, int size) {
        Optional<List<FeedItemDto>> staleOpt = staleCache.get(memberId, lat, lon, page, size);
        if (staleOpt.isPresent()) {
            log.info("[Feed Stale Hit] memberId={}, page={}", memberId, page);
            return staleOpt.get();
        }

        List<FeedItemDto> fallbackItems = esFallback.getHomeFeed(lat, lon, page, size);
        staleCache.putFromFallback(memberId, lat, lon, page, size, fallbackItems);
        return fallbackItems;
    }
}