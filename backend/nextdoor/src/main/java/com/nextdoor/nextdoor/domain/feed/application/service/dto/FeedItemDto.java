package com.nextdoor.nextdoor.domain.feed.application.service.dto;
import lombok.Builder;

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

    public static FeedItemDto fromSummary(PostSummary summary) {
        return FeedItemDto.builder()
                .postId(summary.id())
                .title(summary.title())
                .category(summary.category())
                .rentalFee(summary.rentalFee())
                .deposit(summary.deposit())
                .createdAt(summary.createdAt() != null ? summary.createdAt().toString() : "")
                .build();
    }
}