package com.nextdoor.nextdoor.domain.feed.application.usecase;

import com.nextdoor.nextdoor.domain.feed.application.service.dto.FeedItemDto;

import java.util.List;

public interface GetHomeFeedUseCase {

    record FeedResult(List<FeedItemDto> items, boolean hasNext) {}

    FeedResult getHomeFeed(Long memberId, Double lat, Double lon, int page);
}
