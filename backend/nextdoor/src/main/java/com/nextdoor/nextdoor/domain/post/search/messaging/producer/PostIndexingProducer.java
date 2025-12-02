package com.nextdoor.nextdoor.domain.post.search.messaging.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Profile("api")
@RequiredArgsConstructor
@Slf4j
public class PostIndexingProducer {

    private final MessageProducer messageProducer;
    private final ObjectMapper objectMapper;

    @Value("${sqs.queue.reindex}")
    private String queueName;

    public void requestSinglePostIndexing(Long postId) {
        sendMessage(Map.of("action", "INDEX", "postId", postId));
    }

    public void requestSinglePostDeletion(Long postId) {
        sendMessage(Map.of("action", "DELETE", "postId", postId));
    }

    public void requestReindexAll() {
        sendMessage(Map.of("action", "REINDEX_ALL"));
    }

    private void sendMessage(Object payload) {
        try {
            String messageBody = objectMapper.writeValueAsString(payload);
            messageProducer.sendMessage(queueName, messageBody);
        } catch (Exception e) {
            log.error("Error sending indexing message: {}", e.getMessage(), e);
        }
    }
}