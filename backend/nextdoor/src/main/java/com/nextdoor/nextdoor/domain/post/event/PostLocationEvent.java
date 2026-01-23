package com.nextdoor.nextdoor.domain.post.event;

import lombok.Builder;
import lombok.Getter;

/**
 * 게시글 위치 정보 이벤트
 * Redis Geo 인덱싱을 위한 이벤트
 */
@Getter
@Builder
public class PostLocationEvent {

    private Long postId;
    private Double latitude;
    private Double longitude;
}