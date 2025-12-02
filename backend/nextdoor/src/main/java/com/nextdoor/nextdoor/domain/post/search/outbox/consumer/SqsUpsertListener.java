package com.nextdoor.nextdoor.domain.post.search.outbox.consumer;

import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("worker")
@RequiredArgsConstructor
@Slf4j
public class SqsUpsertListener {

    private final UpsertMessageConsumer consumer;

    @SqsListener(value = "${sqs.queue.upsert}", acknowledgementMode = "MANUAL")
    public void onMessage(String message, Acknowledgement ack) {
        try {
            log.debug("업서트 메시지 수신: {}", message);
            consumer.process(message, ack);
        } catch (Exception e) {
            log.error("업서트 메시지 처리 중 오류 발생: {}", message, e);
        }
    }
}