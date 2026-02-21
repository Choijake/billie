package com.nextdoor.nextdoor.domain.feed.application.port;

import com.nextdoor.nextdoor.domain.feed.domain.GeoPoint;

import java.util.List;

public interface FeedGeoIndexPort {
    List<Long> findNearbyPostIds(GeoPoint center, double radiusKm, int globalLimit);
    void addGeoLocation(Long postId, GeoPoint point);
    void removeGeoLocation(Long postId);
}
