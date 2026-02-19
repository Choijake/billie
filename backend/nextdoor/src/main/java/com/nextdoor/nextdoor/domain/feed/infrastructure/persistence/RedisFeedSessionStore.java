package com.nextdoor.nextdoor.domain.feed.infrastructure.persistence;

import com.nextdoor.nextdoor.domain.feed.application.port.FeedSessionStore;
import com.nextdoor.nextdoor.domain.feed.domain.Exception.FeedRedisUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisFeedSessionStore implements FeedSessionStore {

    private final StringRedisTemplate redisTemplate;
    private final RedisCallExecutor redisCallExecutor;
    private final FeedSessionKeyFactory keyFactory;

    private <T> T redisFailFast(String opName, java.util.function.Supplier<T> supplier) {
        try {
            return redisCallExecutor.call(supplier);
        } catch (Exception e) {
            throw new FeedRedisUnavailableException("[Redis Fail] op=" + opName, e);
        }
    }

    private void redisFailFastRun(String opName, Runnable runnable) {
        try {
            redisCallExecutor.run(runnable);
        } catch (Exception e) {
            throw new FeedRedisUnavailableException("[Redis Fail] op=" + opName, e);
        }
    }

    private void redisBestEffort(String opName, Runnable runnable) {
        try {
            if (redisCallExecutor.isOpen()) {
                log.warn("[Redis Skip - CB OPEN] op={}", opName);
                return;
            }
            redisCallExecutor.run(runnable);
        } catch (Exception e) {
            log.warn("[Redis BestEffort Failed] op={}, err={}", opName, e.toString());
        }
    }

    @Override
    public boolean hasValidSession(Long memberId) {
        return redisFailFast("hasValidSession", () -> {
            String pointerKey = keyFactory.pointerKey(memberId);
            String dataKey = redisTemplate.opsForValue().get(pointerKey);
            if (!StringUtils.hasText(dataKey)) return false;
            return Boolean.TRUE.equals(redisTemplate.hasKey(dataKey));
        });
    }

    @Override
    public void saveSession(Long memberId, List<Long> postIds, Duration ttl) {
        if (postIds == null || postIds.isEmpty()) return;

        redisFailFastRun("saveSession", () -> {
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
        redisBestEffort("touchSession", () -> {
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
    public List<Long> getSessionPage(Long memberId, int page, int size) {
        return redisFailFast("getSessionPage", () -> {
            String pointerKey = keyFactory.pointerKey(memberId);
            String dataKey = redisTemplate.opsForValue().get(pointerKey);
            if (!StringUtils.hasText(dataKey)) return Collections.emptyList();

            long start = (long) page * size;
            long end = start + size - 1;

            Set<String> ids = redisTemplate.opsForZSet().range(dataKey, start, end);
            if (ids == null || ids.isEmpty()) return Collections.emptyList();

            return ids.stream().map(Long::parseLong).toList();
        });
    }
}