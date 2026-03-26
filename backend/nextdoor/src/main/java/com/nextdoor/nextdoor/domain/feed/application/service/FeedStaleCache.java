package com.nextdoor.nextdoor.domain.feed.application.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nextdoor.nextdoor.domain.feed.application.service.dto.FeedItemDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class FeedStaleCache {

    private final Cache<Key, Entry> cache;
    private final boolean enabled;
    private final int geoRoundDecimals;
    private final int cacheMaxPage;

    public FeedStaleCache(
            @Value("${feed.stale-cache.enabled:true}") boolean enabled,
            @Value("${feed.stale-cache.ttl-seconds:45}") long ttlSeconds,
            @Value("${feed.stale-cache.max-entries:20000}") long maxEntries,
            @Value("${feed.stale-cache.geo-round-decimals:3}") int geoRoundDecimals,
            @Value("${feed.stale-cache.cache-max-page:3}") int cacheMaxPage
    ) {
        this.enabled = enabled;
        this.geoRoundDecimals = Math.max(0, geoRoundDecimals);
        this.cacheMaxPage = Math.max(0, cacheMaxPage);
        this.cache = Caffeine.newBuilder()
                .maximumSize(Math.max(1L, maxEntries))
                .expireAfterWrite(Duration.ofSeconds(Math.max(1L, ttlSeconds)))
                .build();
    }

    public Optional<List<FeedItemDto>> get(Long memberId, Double lat, Double lon, int page, int size) {
        if (!enabled) return Optional.empty();
        Optional<Key> keyOpt = toKey(memberId, lat, lon, page, size);
        if (keyOpt.isEmpty()) return Optional.empty();

        Entry entry = cache.getIfPresent(keyOpt.get());
        if (entry == null) return Optional.empty();
        return Optional.of(entry.items());
    }

    public void putFromPrimary(Long memberId, Double lat, Double lon, int page, int size, List<FeedItemDto> items) {
        put(memberId, lat, lon, page, size, items, Source.PRIMARY);
    }

    public void putFromFallback(Long memberId, Double lat, Double lon, int page, int size, List<FeedItemDto> items) {
        put(memberId, lat, lon, page, size, items, Source.FALLBACK_ES);
    }

    private void put(Long memberId, Double lat, Double lon, int page, int size, List<FeedItemDto> items, Source source) {
        if (!enabled || items == null || items.isEmpty()) return;
        Optional<Key> keyOpt = toKey(memberId, lat, lon, page, size);
        if (keyOpt.isEmpty()) return;

        cache.put(keyOpt.get(), new Entry(List.copyOf(items), Instant.now(), source));
    }

    private Optional<Key> toKey(Long memberId, Double lat, Double lon, int page, int size) {
        if (memberId == null || lat == null || lon == null) return Optional.empty();
        if (page < 0 || size <= 0 || page > cacheMaxPage) return Optional.empty();

        int scale = (int) Math.pow(10, geoRoundDecimals);
        int latBucket = (int) Math.round(lat * scale);
        int lonBucket = (int) Math.round(lon * scale);
        return Optional.of(new Key(memberId, latBucket, lonBucket, page, size));
    }

    private record Key(Long memberId, int latBucket, int lonBucket, int page, int size) {}

    private record Entry(List<FeedItemDto> items, Instant cachedAt, Source source) {}

    private enum Source {
        PRIMARY,
        FALLBACK_ES
    }
}
