package com.nextdoor.nextdoor.domain.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class IndexLockService {

    private static final String FULL_INDEX_LOCK = "FULL_INDEX_LOCK";
    private static final String PENDING_INDEX_QUEUE= "PENDING_INDEX_QUEUE";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public boolean acquireFullIndexLock() {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                FULL_INDEX_LOCK,
                "LOCKED",
                2,
                TimeUnit.HOURS
        ));
    }

    public void releaseFullIndexLock() {
        redisTemplate.delete(FULL_INDEX_LOCK);
    }

    public boolean isFullIndexLocked(){
        return redisTemplate.hasKey(FULL_INDEX_LOCK);
    }

    public void addToPendingIndexQueue(PostDocument postDocument) {
        try {
            String jsonDoc = objectMapper.writeValueAsString(postDocument);
            redisTemplate.opsForList().rightPush(PENDING_INDEX_QUEUE, jsonDoc);
        } catch (Exception e) {
            throw new RuntimeException("인덱싱 대기 큐에 등록 실패: " + e.getMessage(), e);
        }
    }

    public List<PostDocument> processPendingIndexQueue() {
        List<String> jsonDocuments = redisTemplate.opsForList().range(PENDING_INDEX_QUEUE, 0, -1);
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
