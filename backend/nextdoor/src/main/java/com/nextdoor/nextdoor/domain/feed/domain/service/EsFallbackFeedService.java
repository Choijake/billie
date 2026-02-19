package com.nextdoor.nextdoor.domain.feed.domain.service;

import com.nextdoor.nextdoor.domain.feed.application.port.FeedSearchPort;
import com.nextdoor.nextdoor.domain.feed.application.service.dto.FeedItemDto;
import com.nextdoor.nextdoor.domain.feed.application.service.dto.PostSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EsFallbackFeedService {

    private final FeedSearchPort feedSearchPort;

    public List<FeedItemDto> getHomeFeed(Long memberId, Double lat, Double lon, int page, int size) {
        if (lat == null || lon == null) return List.of();

        List<PostSummary> summaries = feedSearchPort.searchNearbyFast(lat, lon, page, size);

        return summaries.stream()
                .map(FeedItemDto::fromSummary)
                .toList();
    }
}
