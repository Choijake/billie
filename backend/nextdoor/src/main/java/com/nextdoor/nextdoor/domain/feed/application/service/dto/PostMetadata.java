package com.nextdoor.nextdoor.domain.feed.application.service.dto;

import com.nextdoor.nextdoor.domain.post.domain.Category;
import com.nextdoor.nextdoor.domain.post.port.PostFeedMetadata;
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

    public static PostMetadata fromFeedMetadata(PostFeedMetadata meta) {
        if (meta == null) return null;

        return PostMetadata.builder()
                .postId(meta.id())
                .title(meta.title())
                .category(meta.category())
                .rentalFee(meta.rentalFee())
                .deposit(meta.deposit())
                .createdAt(meta.createdAt())
                .build();
    }
}