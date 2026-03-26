package com.nextdoor.nextdoor.domain.feed.infrastructure.metadata;

import com.nextdoor.nextdoor.domain.feed.application.port.PostMetadataCachePort;
import com.nextdoor.nextdoor.domain.feed.infrastructure.redis.RedisExecution;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

@Component
@RequiredArgsConstructor
public class RedisPostMetadataCacheAdapter implements PostMetadataCachePort {

    private final StringRedisTemplate redisTemplate;
    private final RedisExecution redis;

    @Override
    public List<String> multiGetRaw(List<String> keys) {
        return redis.failFast("metadata.multiGetRaw", () -> redisTemplate.opsForValue().multiGet(keys));
    }

    @Override
    public void setAllRaw(Map<String, String> kv, Duration ttl) {
        if (kv == null || kv.isEmpty()) return;

        redis.failFastRun("metadata.setAllRaw", () -> {
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                long seconds = ttl.toSeconds();
                for (Map.Entry<String, String> e : kv.entrySet()) {
                    connection.stringCommands().setEx(e.getKey().getBytes(), seconds, e.getValue().getBytes());
                }
                return null;
            });
        });
    }

    @Override
    public void markDeleted(Collection<String> keys, String tombstoneValue, Duration ttl) {
        if (keys == null || keys.isEmpty()) return;

        redis.failFastRun("metadata.markDeleted", () -> {
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                long seconds = ttl.toSeconds();
                for (String key : keys) {
                    connection.stringCommands().setEx(key.getBytes(), seconds, tombstoneValue.getBytes());
                }
                return null;
            });
        });
    }
}