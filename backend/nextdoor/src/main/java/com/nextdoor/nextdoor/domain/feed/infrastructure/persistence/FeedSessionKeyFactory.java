package com.nextdoor.nextdoor.domain.feed.infrastructure.persistence;

public interface FeedSessionKeyFactory {
    String pointerKey(Long memberId);
    String dataKey(Long memberId);
}