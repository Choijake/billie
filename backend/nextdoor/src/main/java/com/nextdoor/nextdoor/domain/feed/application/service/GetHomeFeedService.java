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
    public FeedResult getHomeFeed(Long memberId, Double lat, Double lon, int page) {
        Optional<GeoPoint> pointOpt = GeoPoint.of(lat, lon);
        if (pointOpt.isEmpty()) return new FeedResult(Collections.emptyList(), false);

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

    private FeedResult getFromPrimary(Long memberId, Double lat, Double lon, int page, int size,
                                      long offset, int windowSize, GeoPoint point) {
        List<Long> windowIds = sessionService.getIdsForWindow(memberId, offset, windowSize, point);
        if (windowIds.isEmpty()) return new FeedResult(Collections.emptyList(), false);

        Map<Long, PostMetadata> metadataMap = metadataService.getPostMetadata(windowIds);

        // size + 1개를 요청하여 다음 페이지 존재 여부 판단
        FeedPageComposer.Result result = pageComposer.compose(windowIds, metadataMap, size + 1);

        if (!result.missingIds().isEmpty()) {
            bestEffortExecutor.run("feed.session.prune",
                    () -> sessionService.pruneFromSession(memberId, result.missingIds()));
        }

        List<FeedItemDto> items = result.items();
        boolean hasNext = items.size() > size;
        if (hasNext) {
            items = items.subList(0, size);
        }

        staleCache.putFromPrimary(memberId, lat, lon, page, size, items);
        return new FeedResult(items, hasNext);
    }

    private FeedResult resolveWithStaleThenFallback(Long memberId, Double lat, Double lon, int page, int size) {
        Optional<List<FeedItemDto>> staleOpt = staleCache.get(memberId, lat, lon, page, size);
        if (staleOpt.isPresent()) {
            log.info("[Feed Stale Hit] memberId={}, page={}", memberId, page);
            List<FeedItemDto> items = staleOpt.get();
            return new FeedResult(items, items.size() >= size);
        }

        List<FeedItemDto> fallbackItems = esFallback.getHomeFeed(lat, lon, page, size);
        staleCache.putFromFallback(memberId, lat, lon, page, size, fallbackItems);
        return new FeedResult(fallbackItems, fallbackItems.size() >= size);
    }
}