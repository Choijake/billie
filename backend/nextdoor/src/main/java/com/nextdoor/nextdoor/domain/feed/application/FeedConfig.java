package com.nextdoor.nextdoor.domain.feed.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class FeedConfig {

    private final int pageSize;
    private final int sessionSize;
    private final int geoLimit;
    private final int searchRadiusKm;

    private final int backfillExtraWindow;
    private final Duration sessionTtl;

    private final Duration maxSessionTtl;
    private final Duration metadataTtl;
    private final Duration deletedTtl;
    private final long decayConstantSeconds;

    public FeedConfig(
            @Value("${feed.page-size:10}") int pageSize,
            @Value("${feed.session.size:150}") int sessionSize,
            @Value("${feed.geo.limit:200}") int geoLimit,
            @Value("${feed.search.radius-km:5}") int searchRadiusKm,
            @Value("${feed.backfill.extra-window:50}") int backfillExtraWindow,
            @Value("${feed.session.ttl-seconds:1200}") long sessionTtlSeconds,
            @Value("${feed.session.max-ttl-seconds:3600}") long maxSessionTtlSeconds,
            @Value("${feed.metadata.ttl-seconds:3600}") long metadataTtlSeconds,
            @Value("${feed.deleted.ttl-seconds:7200}") long deletedTtlSeconds,
            @Value("${feed.scoring.decay-constant-seconds:86400}") long decayConstantSeconds
    ) {
        this.pageSize = pageSize;
        this.sessionSize = sessionSize;
        this.geoLimit = geoLimit;
        this.searchRadiusKm = searchRadiusKm;
        this.backfillExtraWindow = backfillExtraWindow;
        this.sessionTtl = Duration.ofSeconds(sessionTtlSeconds);
        this.maxSessionTtl = Duration.ofSeconds(maxSessionTtlSeconds);
        this.metadataTtl = Duration.ofSeconds(metadataTtlSeconds);
        this.deletedTtl = Duration.ofSeconds(deletedTtlSeconds);
        this.decayConstantSeconds = decayConstantSeconds;
    }

    public int pageSize() { return pageSize; }
    public int sessionSize() { return sessionSize; }
    public int geoLimit() { return geoLimit; }
    public int searchRadiusKm() { return searchRadiusKm; }
    public int backfillExtraWindow() { return backfillExtraWindow; }
    public Duration sessionTtl() { return sessionTtl; }
    public Duration maxSessionTtl() { return maxSessionTtl; }
    public Duration metadataTtl() { return metadataTtl; }
    public Duration deletedTtl() { return deletedTtl; }
    public long decayConstantSeconds() { return decayConstantSeconds; }
}
