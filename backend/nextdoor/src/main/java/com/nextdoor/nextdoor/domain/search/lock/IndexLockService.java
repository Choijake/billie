package com.nextdoor.nextdoor.domain.search.lock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextdoor.nextdoor.domain.search.config.SearchProperties;
import com.nextdoor.nextdoor.domain.search.document.PostDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class IndexLockService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final SearchProperties props;

    public boolean acquireFullIndexLock() {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                props.getLock().getFullIndexLockKey(),
                "LOCKED",
                props.getLock().getTtlSec(),
                TimeUnit.SECONDS
        ));
    }

    public void releaseFullIndexLock() {
        redisTemplate.delete(props.getLock().getFullIndexLockKey());
    }

    public boolean isFullIndexLocked(){
        return redisTemplate.hasKey(props.getLock().getFullIndexLockKey());
    }

    public void addToPendingIndexQueue(PostDocument postDocument) {
        try {
            String jsonDoc = objectMapper.writeValueAsString(postDocument);
            redisTemplate.opsForList().rightPush(props.getLock().getPendingQueueKey(), jsonDoc);
        } catch (Exception e) {
            throw new RuntimeException("인덱싱 대기 큐에 등록 실패: " + e.getMessage(), e);
        }
    }

    public List<PostDocument> processPendingIndexQueue() {
        List<String> jsonDocuments = redisTemplate.opsForList().range(props.getLock().getPendingQueueKey(), 0, -1);
        List<PostDocument> documents = new ArrayList<>();

        for (String jsonDocument : jsonDocuments) {
            try {
                documents.add(objectMapper.readValue(jsonDocument, PostDocument.class));
            } catch (Exception e) {
                throw new RuntimeException("인덱싱 대기 큐에서 읽어오기 실패: " + e.getMessage(), e);
            }
        }

        return documents;
    }
}
