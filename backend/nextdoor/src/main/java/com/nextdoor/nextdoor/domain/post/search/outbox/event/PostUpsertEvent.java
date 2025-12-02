package com.nextdoor.nextdoor.domain.post.search.outbox.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor(force = true)
@AllArgsConstructor
public class PostUpsertEvent implements PostIndexEvent {
    private final String type = "UPSERT";
    private Long postId;
    private Long version;
    private String title;
    private String content;
    private Long rentalFee;
    private Long deposit;
    private String address;
    private Double lat;
    private Double lon;
    private String category;
    private Integer likeCount;
    private String createdAtIso;
}