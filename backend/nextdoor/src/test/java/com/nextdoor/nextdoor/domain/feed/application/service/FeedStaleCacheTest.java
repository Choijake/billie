package com.nextdoor.nextdoor.domain.feed.application.service;

import com.nextdoor.nextdoor.domain.feed.application.service.dto.FeedItemDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

class FeedStaleCacheTest {

    @Test
    void 같은_위치버킷_같은_페이지면_stale를_조회한다() {
        // given
        FeedStaleCache cache = fixtureEnabledCache();
        Long memberId = 1L;
        int page = 0;
        int size = 10;
        List<FeedItemDto> items = fixtureItems(2);

        cache.putFromPrimary(memberId, 37.4984, 127.0274, page, size, items);

        // when
        Optional<List<FeedItemDto>> found = cache.get(memberId, 37.49849, 127.02749, page, size);

        // then
        assertSoftly(softly -> {
            softly.assertThat(found).isPresent();
            softly.assertThat(found.orElseThrow()).containsExactlyElementsOf(items);
        });
    }

    @Test
    void cache_max_page를_초과하면_저장하지_않는다() {
        // given
        FeedStaleCache cache = fixtureEnabledCache();
        Long memberId = 2L;
        int overPage = 4;
        int size = 10;

        // when
        cache.putFromPrimary(memberId, 37.5, 127.0, overPage, size, fixtureItems(1));
        Optional<List<FeedItemDto>> found = cache.get(memberId, 37.5, 127.0, overPage, size);

        // then
        assertSoftly(softly -> {
            softly.assertThat(found).isEmpty();
        });
    }

    @Test
    void 빈_리스트는_stale에_저장하지_않는다() {
        // given
        FeedStaleCache cache = fixtureEnabledCache();
        Long memberId = 3L;
        int page = 0;
        int size = 10;

        // when
        cache.putFromFallback(memberId, 37.5, 127.0, page, size, List.of());
        Optional<List<FeedItemDto>> found = cache.get(memberId, 37.5, 127.0, page, size);

        // then
        assertSoftly(softly -> {
            softly.assertThat(found).isEmpty();
        });
    }

    @Test
    void 캐시가_비활성화면_저장및조회를_하지_않는다() {
        // given
        FeedStaleCache disabledCache = new FeedStaleCache(false, 45, 100, 3, 3);
        Long memberId = 4L;
        int page = 0;
        int size = 10;
        List<FeedItemDto> items = fixtureItems(1);

        // when
        disabledCache.putFromPrimary(memberId, 37.5, 127.0, page, size, items);
        Optional<List<FeedItemDto>> found = disabledCache.get(memberId, 37.5, 127.0, page, size);

        // then
        assertSoftly(softly -> {
            softly.assertThat(found).isEmpty();
        });
    }

    private FeedStaleCache fixtureEnabledCache() {
        return new FeedStaleCache(true, 45, 1_000, 3, 3);
    }

    private List<FeedItemDto> fixtureItems(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> FeedItemDto.builder()
                        .postId((long) (100 + i))
                        .title("title-" + i)
                        .category("생활")
                        .rentalFee(1000L + i)
                        .deposit(10000L + i)
                        .imageUrl("https://example.com/" + i + ".jpg")
                        .createdAt("2026-01-01T00:00:00")
                        .build())
                .toList();
    }
}
