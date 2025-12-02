package com.nextdoor.nextdoor.domain.post.search.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextdoor.nextdoor.domain.post.search.outbox.event.PostDeleteEvent;
import com.nextdoor.nextdoor.domain.post.search.outbox.event.PostUpsertEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class Jsons {
    
    private final ObjectMapper objectMapper;
    
    public String wrap(Long version, String payload) {
        try {
            return objectMapper.writeValueAsString(new VersionedPayload(version, payload));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to wrap payload with version", e);
        }
    }
    
    public long readVersion(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.get("version").asLong();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to read version from JSON", e);
        }
    }
    
    public String readPayload(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.get("payload").asText();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to read payload from JSON", e);
        }
    }
    
    public PostUpsertEvent toUpsert(String json) {
        try {
            return objectMapper.readValue(json, PostUpsertEvent.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to convert JSON to PostUpsertEvent", e);
        }
    }
    
    public PostDeleteEvent toDelete(String json) {
        try {
            return objectMapper.readValue(json, PostDeleteEvent.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to convert JSON to PostDeleteEvent", e);
        }
    }
    
    private static class VersionedPayload {
        private final Long version;
        private final String payload;
        
        public VersionedPayload(Long version, String payload) {
            this.version = version;
            this.payload = payload;
        }
        
        public Long getVersion() {
            return version;
        }
        
        public String getPayload() {
            return payload;
        }
    }
}