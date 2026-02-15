package com.nextdoor.nextdoor.domain.feed.application.port;

import com.nextdoor.nextdoor.domain.feed.application.service.dto.PostSummary;

import java.util.List;

public interface FeedSearchPort {
    List<PostSummary> searchNearbyFast(double lat, double lon, int page, int size);
}
