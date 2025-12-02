package com.nextdoor.nextdoor.domain.post.search.messaging.producer;

public interface MessageProducer {

    void sendMessage(String queueName, Object payload);
}