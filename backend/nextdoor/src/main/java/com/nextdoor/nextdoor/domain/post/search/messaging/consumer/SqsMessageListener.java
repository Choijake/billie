package com.nextdoor.nextdoor.domain.post.search.messaging.consumer;

import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("worker")
@RequiredArgsConstructor
@Slf4j
public class SqsMessageListener {

    private final MessageConsumer messageConsumer;

    @Value("${sqs.queue.reindex}")
    private String queueName;

    @SqsListener("${sqs.queue.reindex}")
    public void receiveMessage(String message) {
        try {
            log.info("수신된 메시지: {}", message);
            messageConsumer.processMessage(message);
        } catch (Exception e) {
            log.error(" 메시지 처리 중 예외 발생: {}", message, e);
        }
    }
}