package com.nextdoor.nextdoor.domain.post.search.outbox;

public interface OutboxEventDto {
    Long getId();
    String getAggregateType();
    Long getAggregateId();
    String getEventType();
    String getPayload();
    Long getVersion();
}
