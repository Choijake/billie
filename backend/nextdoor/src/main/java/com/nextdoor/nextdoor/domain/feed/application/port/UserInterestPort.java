package com.nextdoor.nextdoor.domain.feed.application.port;

import com.nextdoor.nextdoor.domain.post.domain.Category;

import java.util.Map;

public interface UserInterestPort {
    Map<Category, Long> getUserInterests(Long memberId);
}
