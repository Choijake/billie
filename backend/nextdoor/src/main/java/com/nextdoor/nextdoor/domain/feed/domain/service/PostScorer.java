package com.nextdoor.nextdoor.domain.feed.domain.service;

import com.nextdoor.nextdoor.domain.feed.application.service.dto.PostMetadata;
import com.nextdoor.nextdoor.domain.post.domain.Category;

import java.util.Map;

public interface PostScorer {
    double calculateScore(PostMetadata metadata, Map<Category, Long> userInterests);
}
