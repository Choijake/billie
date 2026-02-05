package com.nextdoor.nextdoor.domain.feed.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextdoor.nextdoor.domain.feed.application.service.dto.PostMetadata;
import com.nextdoor.nextdoor.domain.feed.domain.UserInterestScore;
import com.nextdoor.nextdoor.domain.post.domain.Category;
import com.nextdoor.nextdoor.domain.post.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Repository
@RequiredArgsConstructor
public class FeedCacheRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final PostRepository postRepository;
    private final UserInterestScoreRepository userInterestScoreRepository;

    private static final String GEO_KEY = "feed:geo";
    private static final String SESSION_KEY_PREFIX = "session:feed:";
    private static final String INTEREST_KEY_PREFIX = "user:interest:";

    // --- Geo ---
    public List<Long> findNearbyPostIds(Double lat, Double lon, double radiusKm, int limit) {
        Point center = new Point(lon, lat);
        Distance radius = new Distance(radiusKm, Metrics.KILOMETERS);

        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redisTemplate.opsForGeo()
                .radius(GEO_KEY, new Circle(center, radius),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .sortAscending().limit(limit));

        if (results == null) return Collections.emptyList();

        return results.getContent().stream()
                .map(res -> Long.parseLong(res.getContent().getName()))
                .collect(Collectors.toList());
    }

    public void addGeoLocation(Long postId, Double lat, Double lon) {
        redisTemplate.opsForGeo().add(GEO_KEY, new Point(lon, lat), String.valueOf(postId));
    }

    // --- 세션 리스트 ---
    public void saveFeedSession(Long memberId, List<Long> postIds, Duration ttl) {
        String key = SESSION_KEY_PREFIX + memberId;
        List<String> idStrs = postIds.stream().map(String::valueOf).toList();

        redisTemplate.delete(key);
        if (!idStrs.isEmpty()) {
            redisTemplate.opsForList().rightPushAll(key, idStrs);
            redisTemplate.expire(key, ttl);
        }
    }

    public List<Long> getFeedSessionPage(Long memberId, int page, int size) {
        String key = SESSION_KEY_PREFIX + memberId;
        long start = (long) page * size;
        long end = start + size - 1;

        List<String> ids = redisTemplate.opsForList().range(key, start, end);
        if (ids == null) return Collections.emptyList();

        return ids.stream().map(Long::parseLong).toList();
    }

    // --- 메타데이터 ---
    public Map<Long, PostMetadata> getPostMetadata(List<Long> postIds) {
        Map<Long, PostMetadata> result = new HashMap<>();
        List<String> keys = postIds.stream().map(id -> "post:" + id + ":info").toList();

        List<String> jsonList = redisTemplate.opsForValue().multiGet(keys);
        if (jsonList == null) jsonList = Collections.emptyList();

        List<Long> missedIds = new ArrayList<>();

        for (int i = 0; i < postIds.size(); i++) {
            Long postId = postIds.get(i);
            String json = (i < jsonList.size()) ? jsonList.get(i) : null;
            if (StringUtils.hasText(json)) {
                try {
                    result.put(postId, objectMapper.readValue(json, PostMetadata.class));
                } catch (JsonProcessingException e) {
                    missedIds.add(postId);
                }
            } else {
                missedIds.add(postId);
            }
        }

        if (!missedIds.isEmpty()) {
            loadFromDBAndCache(missedIds, result);
        }
        return result;
    }

    private void loadFromDBAndCache(List<Long> postIds, Map<Long, PostMetadata> result) {
        var posts = postRepository.findAllById(postIds);
        for (var post : posts) {
            PostMetadata metadata = PostMetadata.fromEntity(post); // Entity 내부에 변환 메서드 권장
            result.put(post.getId(), metadata);
            try {
                String json = objectMapper.writeValueAsString(metadata);
                redisTemplate.opsForValue().set("post:" + post.getId() + ":info", json, Duration.ofHours(1));
            } catch (JsonProcessingException e) {
                log.error("Cache Error", e);
            }
        }
    }

    // --- 관심사 스코어 ---
    public Map<Category, Long> getUserInterests(Long memberId) {
        Map<Category, Long> interests = new HashMap<>();
        Map<Object, Object> redisMap = redisTemplate.opsForHash().entries(INTEREST_KEY_PREFIX + memberId);

        if (!redisMap.isEmpty()) {
            redisMap.forEach((k, v) -> {
                try {
                    interests.put(Category.valueOf((String) k), Long.parseLong((String) v));
                } catch (Exception ignored) {}
            });
        } else {
            // DB Fallback
            List<UserInterestScore> scores = userInterestScoreRepository.findByMemberId(memberId);
            for (UserInterestScore s : scores) interests.put(s.getCategory(), s.getScore());
        }
        return interests;
    }
}