package com.nextdoor.nextdoor.domain.feed.application.service;

import com.nextdoor.nextdoor.domain.feed.application.service.dto.FeedItemDto;
import com.nextdoor.nextdoor.domain.feed.application.service.dto.PostMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FeedPageComposerTest {

    FeedPageComposer composer = new FeedPageComposer();

    @Test
    void 메타데이터가_없는_ID는_피드에_포함되지_않고_missingIds로_수집된다() {
        // given
        Long id1 = 1L; // 살아있는 글
        Long id2 = 2L; // soft delete / tombstone
        Long id3 = 3L; // 살아있는 글

        List<Long> orderedIds = List.of(id1, id2, id3);

        // PostMetadata mock
        PostMetadata md1 = mock(PostMetadata.class);
        PostMetadata md3 = mock(PostMetadata.class);

        when(md1.postId()).thenReturn(id1);
        when(md3.postId()).thenReturn(id3);

        Map<Long, PostMetadata> metadataMap = new HashMap<>();
        metadataMap.put(id1, md1);
        metadataMap.put(id3, md3);

        int limit = 10;

        FeedItemDto dto1 = mock(FeedItemDto.class);
        FeedItemDto dto3 = mock(FeedItemDto.class);

        try (MockedStatic<FeedItemDto> mocked = mockStatic(FeedItemDto.class)) {
            mocked.when(() -> FeedItemDto.from(md1)).thenReturn(dto1);
            mocked.when(() -> FeedItemDto.from(md3)).thenReturn(dto3);

            // when
            FeedPageComposer.Result result = composer.compose(orderedIds, metadataMap, limit);

            // then
            List<FeedItemDto> items = result.items();
            List<Long> missing = result.missingIds();

            // id1, id3만 items로 변환되고
            assertThat(items).containsExactly(dto1, dto3);

            // 메타데이터가 없던 id2만 missingIds로 수집되어야 한다.
            assertThat(missing).containsExactly(id2);
        }
    }
}