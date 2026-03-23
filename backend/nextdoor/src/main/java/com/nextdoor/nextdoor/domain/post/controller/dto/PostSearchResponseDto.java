package com.nextdoor.nextdoor.domain.post.controller.dto;

import com.nextdoor.nextdoor.domain.search.document.PostDocument;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostSearchResponseDto {
    private Long postId;
    private String title;
    private String productImage;
    private Integer rentalFee;
    private Integer deposit;
    private int likeCount;
    private String category;
    private String address;
    private LocalDateTime createdAt;

    public static PostSearchResponseDto from(PostDocument document) {
        return PostSearchResponseDto.builder()
                .postId(document.getId())
                .title(document.getTitle())
                .productImage(null)
                .rentalFee(document.getRentalFee())
                .deposit(document.getDeposit())
                .likeCount(document.getLikeCount())
                .category(document.getCategory())
                .address(document.getAddress())
                .createdAt(document.getCreatedAt())
                .build();
    }
}
