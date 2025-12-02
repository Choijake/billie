package com.nextdoor.nextdoor.domain.post.search.dto;

import com.nextdoor.nextdoor.domain.post.domain.Category;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class PostWithLikeCountDto {

    private final Long postId;
    private final String title;
    private final String content;
    private final Long rentalFee;
    private final Long deposit;
    private final String address;
    private final Double latitude;
    private final Double longitude;
    private final Category category;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    private final Long likeCount;

    public PostWithLikeCountDto(Long postId, String title, String content, Long rentalFee, Long deposit,
                                String address, Double latitude, Double longitude, Category category,
                                LocalDateTime createdAt, LocalDateTime updatedAt, Long likeCount) {
        this.postId = postId;
        this.title = title;
        this.content = content;
        this.rentalFee = rentalFee;
        this.deposit = deposit;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.category = category;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.likeCount = (likeCount != null) ? likeCount : 0L;
    }
}