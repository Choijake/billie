package com.nextdoor.nextdoor.domain.feed.infrastructure.session;

public interface FeedSessionKeyFactory {
    String pointerKey(Long memberId);
    String dataKey(Long memberId);
}