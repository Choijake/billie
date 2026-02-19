package com.nextdoor.nextdoor.domain.feed.application.service.dto;

import lombok.Builder;

/**
 * 피드 아이템 DTO (API 응답용)
 */
@Builder
public record FeedItemDto(
        Long postId,
        String title,
        String category,
        Long rentalFee,
        Long deposit,
        String imageUrl,
        String createdAt
) {
    public static FeedItemDto from(PostMetadata metadata) {
        return FeedItemDto.builder()
                .postId(metadata.postId())
                .title(metadata.title())
                .category(metadata.category().getDisplayName())
                .rentalFee(metadata.rentalFee())
                .deposit(metadata.deposit())
                .imageUrl(metadata.imageUrl())
                .createdAt(metadata.createdAt().toString())
                .build();
    }
}