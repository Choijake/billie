package com.nextdoor.nextdoor.domain.post.search.messaging.consumer;

public interface MessageConsumer {

    void processMessage(String payload) throws Exception;
}