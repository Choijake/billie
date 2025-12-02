package com.nextdoor.nextdoor.domain.post.search.messaging.producer;

import io.awspring.cloud.sqs.operations.SqsTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SqsProducer implements MessageProducer {

    private final SqsTemplate sqsTemplate;

    @Override
    public void sendMessage(String queueName, Object payload) {
        sqsTemplate.send(queueName, payload);
    }
}