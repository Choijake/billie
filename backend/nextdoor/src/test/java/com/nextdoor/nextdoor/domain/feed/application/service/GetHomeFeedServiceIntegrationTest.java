package com.nextdoor.nextdoor.domain.feed.application.service;

import com.nextdoor.nextdoor.domain.feed.application.FeedConfig;
import com.nextdoor.nextdoor.domain.feed.application.service.dto.FeedItemDto;
import com.nextdoor.nextdoor.domain.feed.application.service.dto.PostMetadata;
import com.nextdoor.nextdoor.domain.feed.domain.GeoPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
    EsFallbackFeedService esFallback;

    @Mock
    FeedConfig feedConfig;

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
        FeedItemDto item = mock(FeedItemDto.class); // 실제 DTO 필드는 중요 X
        List<FeedItemDto> items = List.of(item);
        List<Long> missingIds = List.of(200L);

        FeedPageComposer.Result result = new FeedPageComposer.Result(items, missingIds);
        when(pageComposer.compose(windowIds, metadataMap, pageSize)).thenReturn(result);

        // when
        List<FeedItemDto> actual = getHomeFeedService.getHomeFeed(memberId, lat, lon, page);

        // then
        // 1) 반환된 피드는 composer가 만든 items와 동일해야 함
        assertThat(actual).hasSize(1);
        assertThat(actual.get(0)).isSameAs(item);

        // 2) missingIds는 세션에서 prune 대상이 되어야 함
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> missingCaptor = ArgumentCaptor.forClass(List.class);

        verify(sessionService).pruneFromSession(eq(memberId), missingCaptor.capture());
        assertThat(missingCaptor.getValue()).containsExactly(200L);

        // 3) 예외가 없으므로 ES fallback은 호출되지 않아야 정상
        verify(esFallback, never()).getHomeFeed(anyDouble(), anyDouble(), anyInt(), anyInt());
    }
}