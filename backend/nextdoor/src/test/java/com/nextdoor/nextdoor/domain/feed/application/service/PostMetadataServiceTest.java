package com.nextdoor.nextdoor.domain.feed.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextdoor.nextdoor.domain.feed.application.FeedConfig;
import com.nextdoor.nextdoor.domain.feed.application.port.PostMetadataCachePort;
import com.nextdoor.nextdoor.domain.feed.application.port.PostMetadataReadPort;
import com.nextdoor.nextdoor.domain.feed.application.service.dto.PostMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.nextdoor.nextdoor.domain.feed.application.service.PostMetadataService.TOMBSTONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostMetadataServiceTest {

    @Mock
    PostMetadataCachePort cachePort;

    @Mock
    PostMetadataReadPort readPort;

    @Mock
    FeedConfig feedConfig;

    ObjectMapper objectMapper = new ObjectMapper();

    PostMetadataService service;

    @BeforeEach
    void setUp() {
        service = new PostMetadataService(cachePort, readPort, objectMapper, feedConfig);
    }

    @Test
    void 캐시에_tombstone이_있으면_DB를_조회하지_않는다() {
        // given
        Long postId = 1L;
        List<Long> ids = List.of(postId);

        // multiGetRaw 호출 시 tombstone 반환
        when(cachePort.multiGetRaw(anyList()))
                .thenReturn(List.of(TOMBSTONE));

        // when
        Map<Long, PostMetadata> result = service.getPostMetadata(ids);

        // then
        assertThat(result).isEmpty();

        // tombstone이면 missed에 추가되지 않으므로 DB를 조회하지 않는다.
        verify(readPort, never()).loadByIds(anyList());
        // tombstone은 이미 있으므로 markDeleted도 호출 안 됨
        verify(cachePort, never()).markDeleted(anyCollection(), anyString(), any());
    }

    @Test
    void 캐시미스_그리고_DB에도_없으면_tombstone을_기록한다() {
        // given
        Long deletedId = 42L;
        List<Long> ids = List.of(deletedId);

        // 캐시는 미스 (null 또는 빈 문자열)
        when(cachePort.multiGetRaw(anyList()))
                .thenReturn(Collections.singletonList(null));

        // DB에서도 해당 ID 없음 (soft delete 등으로 인해 조회 결과 X)
        when(readPort.loadByIds(ids)).thenReturn(Map.of());

        Duration deletedTtl = Duration.ofMinutes(30);
        when(feedConfig.deletedTtl()).thenReturn(deletedTtl);

        // when
        Map<Long, PostMetadata> result = service.getPostMetadata(ids);

        // then
        assertThat(result).isEmpty();

        // DB는 한 번 조회해야 한다.
        verify(readPort).loadByIds(ids);

        // tombstone 기록을 위해 markDeleted 호출되었는지 검증
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> keyCaptor = ArgumentCaptor.forClass(Collection.class);

        verify(cachePort).markDeleted(
                keyCaptor.capture(),
                eq(TOMBSTONE),
                eq(deletedTtl)
        );

        Collection<String> keys = keyCaptor.getValue();
        assertThat(keys).hasSize(1);
        String key = keys.iterator().next();
        // metaKey(postId) 규칙: "post:{id}:info"
        assertThat(key).isEqualTo("post:" + deletedId + ":info");
    }

    @Test
    void markDeleted_메서드는_단일_postId에_대해_tombstone을_기록한다() {
        // given
        Long postId = 100L;
        Duration deletedTtl = Duration.ofMinutes(30);
        when(feedConfig.deletedTtl()).thenReturn(deletedTtl);

        // when
        service.markDeleted(postId);

        // then
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> keyCaptor = ArgumentCaptor.forClass(Collection.class);

        verify(cachePort).markDeleted(
                keyCaptor.capture(),
                eq(TOMBSTONE),
                eq(deletedTtl)
        );

        Collection<String> keys = keyCaptor.getValue();
        assertThat(keys).hasSize(1);
        assertThat(keys.iterator().next()).isEqualTo("post:" + postId + ":info");
    }
}