package com.nextdoor.nextdoor.domain.feed.application.usecase;

import com.nextdoor.nextdoor.domain.feed.application.service.dto.FeedItemDto;

import java.util.List;

public interface GetHomeFeedUseCase {
    List<FeedItemDto> getHomeFeed(Long memberId, Double lat, Double lon, int page);
}
