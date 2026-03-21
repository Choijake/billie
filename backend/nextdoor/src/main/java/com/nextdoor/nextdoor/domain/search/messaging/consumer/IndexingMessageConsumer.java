package com.nextdoor.nextdoor.domain.search.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextdoor.nextdoor.domain.search.indexing.ReindexOrchestrator;
import com.nextdoor.nextdoor.domain.search.indexing.SinglePostIndexer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * SQS 메시지 라우팅 전담. (SRP)
 * 이전에는 라우팅 + 단건 색인 + 단건 삭제 + 전체 리인덱싱 오케스트레이션이 혼재했으나,
 * 각 실행 책임은 SinglePostIndexer / ReindexOrchestrator로 위임한다.
 */
@Slf4j
@Service
@Profile("worker")
@RequiredArgsConstructor
public class IndexingMessageConsumer implements MessageConsumer {

    private final SinglePostIndexer singlePostIndexer;
    private final ReindexOrchestrator reindexOrchestrator;
    private final ObjectMapper objectMapper;

    @Override
    public void processMessage(String payload) throws Exception {
        Map<String, Object> message = objectMapper.readValue(payload, Map.class);
        String action = (String) message.get("action");

        switch (action) {
            case "REINDEX_ALL" -> reindexOrchestrator.reindexAll();
            case "INDEX"       -> singlePostIndexer.indexSinglePost(extractPostId(message));
            case "DELETE"      -> singlePostIndexer.deleteSingleIndex(extractPostId(message));
            default            -> log.warn("처리할 수 없는 인덱싱 액션: {}", action);
        }
    }

    private Long extractPostId(Map<String, Object> message) {
        return ((Number) message.get("postId")).longValue();
    }
}
