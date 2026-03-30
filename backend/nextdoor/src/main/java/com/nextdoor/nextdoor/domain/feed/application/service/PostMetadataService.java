package com.nextdoor.nextdoor.domain.feed.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextdoor.nextdoor.domain.feed.application.FeedConfig;
import com.nextdoor.nextdoor.domain.feed.application.port.PostMetadataCachePort;
import com.nextdoor.nextdoor.domain.feed.application.port.PostMetadataReadPort;
import com.nextdoor.nextdoor.domain.feed.application.service.dto.PostMetadata;
import com.nextdoor.nextdoor.domain.feed.infrastructure.exception.InfraException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;

@Service
@RequiredArgsConstructor
public class PostMetadataService {

    public static final String TOMBSTONE = "__DELETED__";

    private final PostMetadataCachePort cachePort;
    private final PostMetadataReadPort readPort;
    private final ObjectMapper objectMapper;
    private final FeedConfig feedConfig;
    private final BestEffortExecutor bestEffortExecutor;

    public Map<Long, PostMetadata> getPostMetadata(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) return Collections.emptyMap();

        try {
            return loadWithCacheFirst(postIds);
        } catch (InfraException cacheFailure) {
            return loadFromDbOnly(postIds);
        }
    }

    public void evict(Long postId) {
        if (postId == null) return;
        bestEffortExecutor.run(
                "feed.metadata.evict",
                () -> cachePort.evict(metaKey(postId))
        );
    }

    public void markDeleted(Long postId) {
        if (postId == null) return;
        bestEffortExecutor.run(
                "feed.metadata.markDeleted",
                () -> cachePort.markDeleted(List.of(metaKey(postId)), TOMBSTONE, feedConfig.deletedTtl())
        );
    }

    private Map<Long, PostMetadata> loadWithCacheFirst(List<Long> postIds) {
        List<String> keys = postIds.stream().map(this::metaKey).toList();
        List<String> raws = cachePort.multiGetRaw(keys);

        Map<Long, PostMetadata> hit = new HashMap<>();
        List<Long> missed = new ArrayList<>();

        for (int i = 0; i < postIds.size(); i++) {
            Long id = postIds.get(i);
            String raw = (raws != null && i < raws.size()) ? raws.get(i) : null;

            if (!StringUtils.hasText(raw)) {
                missed.add(id);
                continue;
            }

            if (TOMBSTONE.equals(raw)) {
                continue;
            }

            try {
                hit.put(id, objectMapper.readValue(raw, PostMetadata.class));
            } catch (Exception e) {
                missed.add(id);
            }
        }

        if (missed.isEmpty()) return hit;

        Map<Long, PostMetadata> dbMap = readPort.loadByIds(missed);

        Map<String, String> toCache = new HashMap<>();
        Set<Long> found = dbMap.keySet();

        for (Long id : found) {
            PostMetadata md = dbMap.get(id);
            hit.put(id, md);
            try {
                toCache.put(metaKey(id), objectMapper.writeValueAsString(md));
            } catch (Exception ignored) {}
        }

        List<String> deletedKeys = new ArrayList<>();
        for (Long id : missed) {
            if (!found.contains(id)) {
                deletedKeys.add(metaKey(id));
            }
        }

        if (!toCache.isEmpty()) {
            bestEffortExecutor.run(
                    "feed.metadata.cache.setAllRaw",
                    () -> cachePort.setAllRaw(toCache, feedConfig.metadataTtl())
            );
        }
        if (!deletedKeys.isEmpty()) {
            bestEffortExecutor.run(
                    "feed.metadata.cache.markDeleted",
                    () -> cachePort.markDeleted(deletedKeys, TOMBSTONE, feedConfig.deletedTtl())
            );
        }

        return hit;
    }

    private Map<Long, PostMetadata> loadFromDbOnly(List<Long> postIds) {
        Map<Long, PostMetadata> dbMap = readPort.loadByIds(postIds);
        return new HashMap<>(dbMap);
    }

    private String metaKey(Long postId) {
        return "post:" + postId + ":info";
    }
}