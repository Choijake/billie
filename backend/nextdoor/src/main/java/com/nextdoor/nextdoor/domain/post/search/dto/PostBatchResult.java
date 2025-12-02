package com.nextdoor.nextdoor.domain.post.search.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Getter
@RequiredArgsConstructor
public class PostBatchResult {
    private final List<PostWithLikeCountDto> posts;
    private final long lastId;
}