package com.nextdoor.nextdoor.domain.post.event;

import com.nextdoor.nextdoor.domain.post.domain.Category;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class PostViewedEvent {
    private Long memberId; // 게시글을 조회한 사용자 ID
    private Category category; // 게시물 카테고리
}