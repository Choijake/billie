package com.nextdoor.nextdoor.domain.feed.infrastructure.session;

import com.nextdoor.nextdoor.domain.feed.application.port.FeedSessionStore;
import com.nextdoor.nextdoor.domain.feed.infrastructure.redis.RedisExecution;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.*;

@Component
@RequiredArgsConstructor
public class RedisFeedSessionStoreAdapter implements FeedSessionStore {

    private final StringRedisTemplate redisTemplate;
    private final RedisExecution redis;
    private final FeedSessionKeyFactory keyFactory;

    @Override
    public boolean hasValidSession(Long memberId) {
        return redis.failFast("session.hasValidSession", () -> {
            String pointerKey = keyFactory.pointerKey(memberId);
            String dataKey = redisTemplate.opsForValue().get(pointerKey);
            if (!StringUtils.hasText(dataKey)) return false;
            return Boolean.TRUE.equals(redisTemplate.hasKey(dataKey));
        });
    }

    @Override
    public void saveSession(Long memberId, List<Long> postIds, Duration ttl) {
        if (postIds == null || postIds.isEmpty()) return;

        redis.failFastRun("session.saveSession", () -> {
            String pointerKey = keyFactory.pointerKey(memberId);
            String dataKey = keyFactory.dataKey(memberId);

            Set<ZSetOperations.TypedTuple<String>> tuples = new HashSet<>();
            for (int i = 0; i < postIds.size(); i++) {
                tuples.add(new DefaultTypedTuple<>(String.valueOf(postIds.get(i)), (double) i));
            }

            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                byte[] dataKeyBytes = dataKey.getBytes();
                byte[] ptrKeyBytes = pointerKey.getBytes();
                byte[] versionBytes = dataKey.getBytes();

                for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                    connection.zSetCommands().zAdd(dataKeyBytes, tuple.getScore(), tuple.getValue().getBytes());
                }

                connection.keyCommands().expire(dataKeyBytes, ttl.toSeconds());
                connection.stringCommands().set(ptrKeyBytes, versionBytes);
                connection.keyCommands().expire(ptrKeyBytes, ttl.toSeconds());

                return null;
            });
        });
    }

    @Override
    public void touchSession(Long memberId, Duration ttl) {
        redis.failFastRun("session.touchSession", () -> {
            String pointerKey = keyFactory.pointerKey(memberId);
            String dataKey = redisTemplate.opsForValue().get(pointerKey);
            if (!StringUtils.hasText(dataKey)) return;

            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                connection.keyCommands().expire(pointerKey.getBytes(), ttl.toSeconds());
                connection.keyCommands().expire(dataKey.getBytes(), ttl.toSeconds());
                return null;
            });
        });
    }

    @Override
    public List<Long> getSessionRange(Long memberId, long start, long endInclusive) {
        return redis.failFast("session.getSessionRange", () -> {
            String pointerKey = keyFactory.pointerKey(memberId);
            String dataKey = redisTemplate.opsForValue().get(pointerKey);
            if (!StringUtils.hasText(dataKey)) return Collections.emptyList();

            Set<String> ids = redisTemplate.opsForZSet().range(dataKey, start, endInclusive);
            if (ids == null || ids.isEmpty()) return Collections.emptyList();

            return ids.stream().map(Long::parseLong).toList();
        });
    }

    @Override
    public void removeFromSession(Long memberId, Collection<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) return;

        redis.failFastRun("session.removeFromSession", () -> {
            String pointerKey = keyFactory.pointerKey(memberId);
            String dataKey = redisTemplate.opsForValue().get(pointerKey);
            if (!StringUtils.hasText(dataKey)) return;

            Object[] members = postIds.stream().map(String::valueOf).toArray();
            redisTemplate.opsForZSet().remove(dataKey, members);
        });
    }
}
