package com.nextdoor.nextdoor.domain.feed.infrastructure.interest;

import com.nextdoor.nextdoor.domain.feed.application.port.UserInterestPort;
import com.nextdoor.nextdoor.domain.feed.infrastructure.redis.RedisExecution;
import com.nextdoor.nextdoor.domain.post.domain.Category;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class RedisUserInterestAdapter implements UserInterestPort {

    private final StringRedisTemplate redisTemplate;
    private final RedisExecution redis;

    @Override
    public Map<Category, Long> getUserInterests(Long memberId) {
        return redis.failFast("interest.get", () -> {
            String key = "user:" + memberId + ":interest";

            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
            if (entries == null || entries.isEmpty()) return Collections.emptyMap();
            if (entries.containsKey("EMPTY")) return Collections.emptyMap();

            Map<Category, Long> result = new HashMap<>();
            for (Map.Entry<Object, Object> e : entries.entrySet()) {
                try {
                    result.put(Category.valueOf((String) e.getKey()), Long.parseLong((String) e.getValue()));
                } catch (Exception ignored) {}
            }
            return result;
        });
    }
}