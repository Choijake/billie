package com.nextdoor.nextdoor.domain.search.outbox.event;

public interface PostIndexEvent {
    String getType();
    Long getPostId();
    Long getVersion();
}