package com.nextdoor.nextdoor.domain.feed.application.service.dto;

import lombok.Builder;

/**
 * 피드 조회 응답 DTO
 */
@Builder
public record FeedResponse(
        java.util.List<FeedItemDto> items,
        int pageSize,
        boolean hasNext
) {
    public static FeedResponse of(java.util.List<FeedItemDto> items, int pageSize, boolean hasNext) {
        return FeedResponse.builder()
                .items(items)
                .pageSize(items.size())
                .hasNext(hasNext)
                .build();
    }
}