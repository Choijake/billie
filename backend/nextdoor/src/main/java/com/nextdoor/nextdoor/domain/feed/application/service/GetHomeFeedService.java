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
    private final EsFallbackFeedService esFallback;

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
            // 1 세션 window 조회
            List<Long> windowIds = sessionService.getIdsForWindow(memberId, offset, windowSize, point);
            if (windowIds.isEmpty()) return Collections.emptyList();

            // 2 메타 조회
            Map<Long, PostMetadata> metadataMap = metadataService.getPostMetadata(windowIds);

            // 3 페이지 구성 + 누락 id 수집
            FeedPageComposer.Result result = pageComposer.compose(windowIds, metadataMap, size);

            // 4 누락/삭제 id는 세션에서 제거
            if (!result.missingIds().isEmpty()) {
                try {
                    sessionService.pruneFromSession(memberId, result.missingIds());
                } catch (InfraException ignored) {
                    // prune은 best-effort
                }
            }

            return result.items();

        } catch (InfraException e) {
            log.warn("[Feed Infra Failure → ES Fallback] memberId={}, page={}, err={}",
                    memberId, page, e.getMessage());
            return esFallback.getHomeFeed(lat, lon, page, size);

        } catch (Exception e) {
            log.error("[Feed Unexpected → ES Fallback] memberId={}, page={}, err={}",
                    memberId, page, e.toString());
            return esFallback.getHomeFeed(lat, lon, page, size);
        }
    }
}