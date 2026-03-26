package com.nextdoor.nextdoor.domain.feed.domain;

import java.util.Optional;

public record GeoPoint(double lat, double lon) {

    public static Optional<GeoPoint> of(Double lat, Double lon) {
        if (lat == null || lon == null) return Optional.empty();
        if (lat < -90.0 || lat > 90.0) return Optional.empty();
        if (lon < -180.0 || lon > 180.0) return Optional.empty();
        return Optional.of(new GeoPoint(lat, lon));
    }
}
