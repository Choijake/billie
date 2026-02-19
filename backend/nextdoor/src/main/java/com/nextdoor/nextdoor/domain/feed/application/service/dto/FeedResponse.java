package com.nextdoor.nextdoor.domain.feed.application.service.dto;

import lombok.Builder;

/**
 * 피드 조회 응답 DTO
 */
@Builder
public record FeedResponse(
        java.util.List<FeedItemDto> items,
        int totalCount
) {
    public static FeedResponse of(java.util.List<FeedItemDto> items) {
        return FeedResponse.builder()
                .items(items)
                .totalCount(items.size())
                .build();
    }
}