package com.nextdoor.nextdoor.domain.post.search.outbox.event;

public interface PostIndexEvent {
    String getType();
    Long getPostId();
    Long getVersion();
}