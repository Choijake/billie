package com.nextdoor.nextdoor.domain.feed.application.port;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface PostMetadataCachePort {
    List<String> multiGetRaw(List<String> keys);
    void setAllRaw(Map<String, String> kv, Duration ttl);
    void markDeleted(Collection<String> keys, String tombstoneValue, Duration ttl);
    void evict(String key);
}
