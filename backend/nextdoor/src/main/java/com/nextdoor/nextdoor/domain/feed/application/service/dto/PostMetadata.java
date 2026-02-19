package com.nextdoor.nextdoor.domain.feed.application.service.dto;

import com.nextdoor.nextdoor.domain.post.domain.Category;
import com.nextdoor.nextdoor.domain.post.domain.Post;
import lombok.Builder;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 캐싱용 게시글 메타데이터
 */
@Builder
public record PostMetadata(
        Long postId,
        String title,
        Category category,
        Long rentalFee,
        Long deposit,
        LocalDateTime createdAt,
        String imageUrl
) implements Serializable {

    public static PostMetadata fromEntity(Post post) {
        String firstImageUrl = null;
        if (post.getProductImages() != null && !post.getProductImages().isEmpty()) {
            firstImageUrl = post.getProductImages().get(0).getImageUrl();
        }

        return PostMetadata.builder()
                .postId(post.getId())
                .title(post.getTitle())
                .category(post.getCategory())
                .rentalFee(post.getRentalFee())
                .deposit(post.getDeposit())
                .createdAt(post.getCreatedAt())
                .imageUrl(firstImageUrl)
                .build();
    }
}