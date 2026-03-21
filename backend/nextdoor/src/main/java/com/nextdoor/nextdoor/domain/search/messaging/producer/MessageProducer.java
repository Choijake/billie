package com.nextdoor.nextdoor.domain.search.messaging.producer;

public interface MessageProducer {

    void sendMessage(String queueName, Object payload);
}