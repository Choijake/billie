package com.nextdoor.nextdoor.domain.feed.application.service;

import com.nextdoor.nextdoor.domain.feed.application.service.dto.FeedItemDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

@SpringBootTest(classes = FeedStaleCacheIntegrationTest.Config.class)
@Transactional
class FeedStaleCacheIntegrationTest {

    @Autowired
    FeedStaleCache staleCache;

    @Test
    void 스프링빈으로_등록된_stale캐시가_저장과_조회를_수행한다() {
        // given
        Long memberId = 11L;
        Double lat = 37.4991;
        Double lon = 127.0281;
        int page = 0;
        int size = 10;
        List<FeedItemDto> items = fixtureItems(2);

        // when
        staleCache.putFromPrimary(memberId, lat, lon, page, size, items);
        Optional<List<FeedItemDto>> found = staleCache.get(memberId, lat, lon, page, size);

        // then
        assertSoftly(softly -> {
            softly.assertThat(found).isPresent();
            softly.assertThat(found.orElseThrow()).containsExactlyElementsOf(items);
        });
    }

    private List<FeedItemDto> fixtureItems(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> FeedItemDto.builder()
                        .postId((long) (200 + i))
                        .title("integration-title-" + i)
                        .category("가전")
                        .rentalFee(2000L + i)
                        .deposit(5000L + i)
                        .imageUrl("https://example.com/integration-" + i + ".jpg")
                        .createdAt("2026-01-01T10:00:00")
                        .build())
                .toList();
    }

    @SpringBootConfiguration
    static class Config {
        @Bean
        FeedStaleCache feedStaleCache() {
            return new FeedStaleCache(true, 45, 10_000, 3, 3);
        }

        @Bean
        PlatformTransactionManager platformTransactionManager() {
            return new PlatformTransactionManager() {
                @Override
                public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
                    return new SimpleTransactionStatus();
                }

                @Override
                public void commit(TransactionStatus status) throws TransactionException {
                    // no-op
                }

                @Override
                public void rollback(TransactionStatus status) throws TransactionException {
                    // no-op
                }
            };
        }
    }
}
