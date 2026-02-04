package com.nextdoor.nextdoor.domain.post.event;

import com.nextdoor.nextdoor.domain.post.domain.Category;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class PostLikedEvent {
    private Long memberId;
    private Category category;
}