package com.nextdoor.nextdoor.domain.feed.application.service;

import com.nextdoor.nextdoor.domain.feed.application.port.FeedSearchPort;
import com.nextdoor.nextdoor.domain.feed.application.service.dto.FeedItemDto;
import com.nextdoor.nextdoor.domain.feed.application.service.dto.PostSummary;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Supplier;

@Slf4j
@Service
public class EsFallbackFeedService {

    private final FeedSearchPort feedSearchPort;
    private final CircuitBreaker feedEsFallbackCircuitBreaker;
    private final Bulkhead feedEsFallbackBulkhead;
    private final RateLimiter feedEsFallbackRateLimiter;

    public EsFallbackFeedService(
            FeedSearchPort feedSearchPort,
            @Qualifier("feedEsFallbackCircuitBreaker") CircuitBreaker feedEsFallbackCircuitBreaker,
            @Qualifier("feedEsFallbackBulkhead") Bulkhead feedEsFallbackBulkhead,
            @Qualifier("feedEsFallbackRateLimiter") RateLimiter feedEsFallbackRateLimiter
    ) {
        this.feedSearchPort = feedSearchPort;
        this.feedEsFallbackCircuitBreaker = feedEsFallbackCircuitBreaker;
        this.feedEsFallbackBulkhead = feedEsFallbackBulkhead;
        this.feedEsFallbackRateLimiter = feedEsFallbackRateLimiter;
    }

    public List<FeedItemDto> getHomeFeed(Double lat, Double lon, int page, int size) {
        if (lat == null || lon == null) return List.of();

        Supplier<List<PostSummary>> origin = () -> feedSearchPort.searchNearbyFast(lat, lon, page, size);
        Supplier<List<PostSummary>> limited = RateLimiter.decorateSupplier(feedEsFallbackRateLimiter, origin);
        Supplier<List<PostSummary>> isolated = Bulkhead.decorateSupplier(feedEsFallbackBulkhead, limited);
        Supplier<List<PostSummary>> guarded = CircuitBreaker.decorateSupplier(feedEsFallbackCircuitBreaker, isolated);

        final List<PostSummary> summaries;
        try {
            summaries = guarded.get();
        } catch (RequestNotPermitted | BulkheadFullException e) {
            log.warn("[Feed ES Fallback Guarded] denied page={}, reason={}", page, e.getClass().getSimpleName());
            return List.of();
        } catch (Exception e) {
            log.warn("[Feed ES Fallback Failed] page={}, err={}", page, e.toString());
            return List.of();
        }

        return summaries.stream()
                .map(FeedItemDto::fromSummary)
                .toList();
    }
}