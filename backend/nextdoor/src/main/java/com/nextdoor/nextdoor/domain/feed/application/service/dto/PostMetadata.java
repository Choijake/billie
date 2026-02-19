package com.nextdoor.nextdoor.domain.feed.application.service.dto;

import com.nextdoor.nextdoor.domain.post.domain.Category;
import lombok.Builder;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 게시글 메타데이터 (Redis 캐싱용)
 *
 * [Serializable 필수]
 * - Redis에 저장하기 위해 직렬화 가능해야 함
 * - GenericJackson2JsonRedisSerializer가 자동 처리
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
) implements Serializable {}