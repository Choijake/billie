package com.nextdoor.nextdoor.domain.post.port;

import com.nextdoor.nextdoor.domain.post.domain.Category;

import java.time.LocalDateTime;

public record PostFeedMetadata(
        Long id,
        String title,
        String content,
        Long rentalFee,
        Long deposit,
        String address,
        Double latitude,
        Double longitude,
        Category category,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}