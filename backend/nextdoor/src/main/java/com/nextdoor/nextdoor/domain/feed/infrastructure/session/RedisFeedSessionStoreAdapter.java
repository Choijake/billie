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

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RedisFeedSessionStoreAdapter implements FeedSessionStore {

    private final StringRedisTemplate redisTemplate;
    private final RedisExecution redis;
    private final FeedSessionKeyFactory keyFactory;
    private final Clock clock;

    @Override
    public Optional<String> resolveValidDataKey(Long memberId, Duration maxTtl) {
        return redis.failFast("session.resolveValidDataKey", () -> {
            String pointerKey = keyFactory.pointerKey(memberId);
            String dataKey = redisTemplate.opsForValue().get(pointerKey);
            if (!StringUtils.hasText(dataKey)) return Optional.empty();
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(dataKey))) return Optional.empty();

            long createdAt = parseCreatedAtMillis(dataKey);
            if ((clock.millis() - createdAt) > maxTtl.toMillis()) return Optional.empty();

            return Optional.of(dataKey);
        });
    }

    private long parseCreatedAtMillis(String dataKey) {
        // dataKey format: "session:data:{memberId}:{epochMillis}-{seq}"
        String suffix = dataKey.substring(dataKey.lastIndexOf(':') + 1);
        return Long.parseLong(suffix.split("-")[0]);
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
    public void touchSession(String dataKey, Duration ttl) {
        if (!StringUtils.hasText(dataKey)) return;

        redis.failFastRun("session.touchSession", () -> {
            // dataKey → pointer key 역산: "session:data:{id}:{ts}" → "session:ptr:{id}"
            String memberId = dataKey.split(":")[2];
            String pointerKey = keyFactory.pointerKey(Long.parseLong(memberId));

            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                connection.keyCommands().expire(pointerKey.getBytes(), ttl.toSeconds());
                connection.keyCommands().expire(dataKey.getBytes(), ttl.toSeconds());
                return null;
            });
        });
    }

    @Override
    public List<Long> getSessionRange(String dataKey, long start, long endInclusive) {
        if (!StringUtils.hasText(dataKey)) return Collections.emptyList();

        return redis.failFast("session.getSessionRange", () -> {
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
