package com.nextdoor.nextdoor.domain.post.controller.dto;

import com.nextdoor.nextdoor.domain.search.document.PostDocument;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostSearchResponseDto {
    private Long postId;
    private String title;
    private String productImage;
    private String rentalFee;
    private String deposit;
    private int likeCount;
    private int dealCount;

    public static PostSearchResponseDto from(PostDocument document) {
        return PostSearchResponseDto.builder()
            .postId(document.getId())
            .title(document.getTitle())
            .productImage(null) // PostDocument doesn't have productImage field
            .rentalFee(String.valueOf(document.getRentalFee()))
            .deposit(String.valueOf(document.getDeposit()))
            .likeCount(document.getLikeCount())
            .dealCount(0) // PostDocument doesn't have dealCount field
            .build();
    }
}
