package com.nextdoor.nextdoor.domain.feed.application.service;

import com.nextdoor.nextdoor.domain.feed.application.FeedConfig;
import com.nextdoor.nextdoor.domain.feed.application.service.dto.FeedItemDto;
import com.nextdoor.nextdoor.domain.feed.application.service.dto.PostMetadata;
import com.nextdoor.nextdoor.domain.feed.domain.GeoPoint;
import com.nextdoor.nextdoor.domain.feed.infrastructure.exception.InfraException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GetHomeFeedServiceIntegrationTest {

    @Mock
    FeedSessionService sessionService;

    @Mock
    PostMetadataService metadataService;

    @Mock
    FeedPageComposer pageComposer;

    @Mock
    FeedStaleCache staleCache;

    @Mock
    EsFallbackFeedService esFallback;

    @Mock
    FeedConfig feedConfig;

    @Spy
    BestEffortExecutor bestEffortExecutor = new BestEffortExecutor();

    @InjectMocks
    GetHomeFeedService getHomeFeedService;

    @Test
    void 세션에는_남아있지만_soft_delete된_게시글은_피드에_포함되지_않고_세션에서_prune된다() {
        // given
        Long memberId = 1L;
        Double lat = 37.498;
        Double lon = 127.027;
        int page = 0;
        int pageSize = 2;

        when(feedConfig.pageSize()).thenReturn(pageSize);
        when(feedConfig.backfillExtraWindow()).thenReturn(0);

        // 1) 세션 window: [100(살아있음), 200(삭제됨)]
        List<Long> windowIds = List.of(100L, 200L);
        when(sessionService.getIdsForWindow(eq(memberId), anyLong(), anyInt(), any(GeoPoint.class)))
                .thenReturn(windowIds);

        // 2) 메타: 100만 존재, 200은 soft delete / tombstone으로 누락된 상태라고 가정
        //    여기서는 실제 PostMetadata 내용은 중요하지 않으므로 dummy map만 준비
        PostMetadata md100 = mock(PostMetadata.class);
        Map<Long, PostMetadata> metadataMap = Map.of(100L, md100);
        when(metadataService.getPostMetadata(windowIds)).thenReturn(metadataMap);

        // 3) 페이지 구성 결과를 직접 지정
        FeedItemDto item = fixtureItem("live-item");
        List<FeedItemDto> items = List.of(item);
        List<Long> missingIds = List.of(200L);

        FeedPageComposer.Result result = new FeedPageComposer.Result(items, missingIds);
        when(pageComposer.compose(windowIds, metadataMap, pageSize + 1)).thenReturn(result);

        // when
        var feedResult = getHomeFeedService.getHomeFeed(memberId, lat, lon, page);
        List<FeedItemDto> actual = feedResult.items();

        // then
        // 1) 반환된 피드는 composer가 만든 items와 동일해야 함
        assertSoftly(softly -> {
            softly.assertThat(actual).hasSize(1);
            softly.assertThat(actual.get(0)).isSameAs(item);
            softly.assertThat(feedResult.hasNext()).isFalse();
        });

        // 2) missingIds는 세션에서 prune 대상이 되어야 함
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> missingCaptor = ArgumentCaptor.forClass(List.class);

        verify(sessionService).pruneFromSession(eq(memberId), missingCaptor.capture());
        assertThat(missingCaptor.getValue()).containsExactly(200L);

        verify(staleCache).putFromPrimary(memberId, lat, lon, page, pageSize, items);

        // 3) 예외가 없으므로 ES fallback은 호출되지 않아야 정상
        verify(esFallback, never()).getHomeFeed(anyDouble(), anyDouble(), anyInt(), anyInt());
    }

    @Test
    void 인프라_예외시_stale캐시_hit이면_ES폴백_호출없이_stale를_반환한다() {
        // given
        Long memberId = 7L;
        Double lat = 37.5;
        Double lon = 127.03;
        int page = 0;
        int pageSize = 10;

        when(feedConfig.pageSize()).thenReturn(pageSize);
        when(feedConfig.backfillExtraWindow()).thenReturn(0);
        when(sessionService.getIdsForWindow(eq(memberId), anyLong(), anyInt(), any(GeoPoint.class)))
                .thenThrow(new InfraException("redis failure"));

        List<FeedItemDto> staleItems = fixtureItems(2);
        when(staleCache.get(memberId, lat, lon, page, pageSize)).thenReturn(Optional.of(staleItems));

        // when
        var feedResult = getHomeFeedService.getHomeFeed(memberId, lat, lon, page);

        // then
        assertThat(feedResult.items()).containsExactlyElementsOf(staleItems);
        verify(esFallback, never()).getHomeFeed(anyDouble(), anyDouble(), anyInt(), anyInt());
        verify(staleCache, never()).putFromFallback(anyLong(), anyDouble(), anyDouble(), anyInt(), anyInt(), anyList());
    }

    @Test
    void 인프라_예외시_stale캐시_miss면_ES폴백_결과를_반환하고_stale에_저장한다() {
        // given
        Long memberId = 9L;
        Double lat = 37.51;
        Double lon = 127.04;
        int page = 1;
        int pageSize = 10;

        when(feedConfig.pageSize()).thenReturn(pageSize);
        when(feedConfig.backfillExtraWindow()).thenReturn(0);
        when(sessionService.getIdsForWindow(eq(memberId), anyLong(), anyInt(), any(GeoPoint.class)))
                .thenThrow(new InfraException("redis failure"));
        when(staleCache.get(memberId, lat, lon, page, pageSize)).thenReturn(Optional.empty());

        List<FeedItemDto> fallbackItems = fixtureItems(3);
        when(esFallback.getHomeFeed(lat, lon, page, pageSize)).thenReturn(fallbackItems);

        // when
        var feedResult = getHomeFeedService.getHomeFeed(memberId, lat, lon, page);

        // then
        assertThat(feedResult.items()).containsExactlyElementsOf(fallbackItems);
        verify(esFallback).getHomeFeed(lat, lon, page, pageSize);
        verify(staleCache).putFromFallback(memberId, lat, lon, page, pageSize, fallbackItems);
    }

    private FeedItemDto fixtureItem(String marker) {
        FeedItemDto item = mock(FeedItemDto.class, marker);
        return item;
    }

    private List<FeedItemDto> fixtureItems(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> fixtureItem("item-" + i))
                .toList();
    }
}
