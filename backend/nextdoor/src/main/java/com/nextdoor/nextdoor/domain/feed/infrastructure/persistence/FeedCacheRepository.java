package com.nextdoor.nextdoor.domain.feed.infrastructure.persistence;

import ch.hsr.geohash.GeoHash;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextdoor.nextdoor.domain.feed.application.service.dto.PostMetadata;
import com.nextdoor.nextdoor.domain.feed.domain.UserInterestScore;
import com.nextdoor.nextdoor.domain.post.domain.Category;
import com.nextdoor.nextdoor.domain.post.repository.PostRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisCallback;
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
    private final MeterRegistry meterRegistry;

    private static final String GEO_KEY_PREFIX = "feed:geo:";
    private static final String SESSION_KEY_PREFIX = "session:feed:";
    private static final String INTEREST_KEY_PREFIX = "user:interest:";
    private static final int GEOHASH_PRECISION = 5;

    /**
     * 위치 기반 조회
     */
    public List<Long> findNearbyPostIds(Double lat, Double lon, double radiusKm, int globalLimit) {
        Timer.Sample totalSample = Timer.start(meterRegistry);

        // 중심점 및 검색 반경 설정
        Point center = new Point(lon, lat);
        Distance radius = new Distance(radiusKm, Metrics.KILOMETERS);
        Circle circle = new Circle(center, radius);

        List<String> targetKeys = getTargetGeohashKeys(lat, lon);

        Timer.Sample redisSample = Timer.start(meterRegistry);
        List<Object> pipelineResults = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String key : targetKeys) {
                connection.geoCommands().geoRadius(
                        key.getBytes(),
                        circle,
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .includeDistance()
                                .sortAscending()
                                .limit(globalLimit)
                );
            }
            return null;
        });
        redisSample.stop(meterRegistry.timer("feed.nearby.step", "step", "redis_geo"));

        if (pipelineResults == null) {
            totalSample.stop(meterRegistry.timer("feed.nearby.total"));
            return Collections.emptyList();
        }
        Timer.Sample streamSample = Timer.start(meterRegistry);

        List<Long> results = pipelineResults.stream()
                .filter(Objects::nonNull)
                .map(obj -> (GeoResults<RedisGeoCommands.GeoLocation<byte[]>>) obj)
                .flatMap(geoResults -> geoResults.getContent().stream())
                .sorted(Comparator.comparingDouble(res -> res.getDistance().getValue()))
                .limit(globalLimit)
                .map(res -> Long.parseLong(new String(res.getContent().getName())))
                .distinct()
                .collect(Collectors.toList());

        streamSample.stop(meterRegistry.timer("feed.nearby.step", "step", "stream_process"));

        totalSample.stop(meterRegistry.timer("feed.nearby.total"));

        return results;
    }

    /**
     * 위치 정보 저장
     */
    public void addGeoLocation(Long postId, Double lat, Double lon) {
        String key = getGeohashKey(lat, lon);
        redisTemplate.opsForGeo().add(key, new Point(lon, lat), String.valueOf(postId));
    }

    private String getGeohashKey(Double lat, Double lon) {
        GeoHash hash = GeoHash.withCharacterPrecision(lat, lon, GEOHASH_PRECISION);
        return GEO_KEY_PREFIX + hash.toBase32();
    }

    private List<String> getTargetGeohashKeys(Double lat, Double lon) {
        GeoHash centerHash = GeoHash.withCharacterPrecision(lat, lon, GEOHASH_PRECISION);
        List<String> keys = new ArrayList<>();

        keys.add(GEO_KEY_PREFIX + centerHash.toBase32());
        for (GeoHash neighbor : centerHash.getAdjacent()) {
            keys.add(GEO_KEY_PREFIX + neighbor.toBase32());
        }
        return keys;
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

    // --- 메타데이터  ---
    public Map<Long, PostMetadata> getPostMetadata(List<Long> postIds) {
        Timer.Sample totalSample = Timer.start(meterRegistry);

        Timer redisTimer = meterRegistry.timer("feed.metadata.step", "step", "redis_get");
        Timer jsonTimer = meterRegistry.timer("feed.metadata.step", "step", "json_parse");
        Timer dbTimer = meterRegistry.timer("feed.metadata.step", "step", "db_fallback");

        Map<Long, PostMetadata> result = new HashMap<>();
        List<String> keys = postIds.stream().map(id -> "post:" + id + ":info").toList();

        List<String> jsonList = redisTimer.record(() ->
                redisTemplate.opsForValue().multiGet(keys)
        );

        if (jsonList == null) jsonList = Collections.emptyList();
        List<Long> missedIds = new ArrayList<>();

        for (int i = 0; i < postIds.size(); i++) {
            Long postId = postIds.get(i);
            String json = (i < jsonList.size()) ? jsonList.get(i) : null;

            if (StringUtils.hasText(json)) {
                jsonTimer.record(() -> {
                    try {
                        result.put(postId, objectMapper.readValue(json, PostMetadata.class));
                    } catch (JsonProcessingException e) {
                        missedIds.add(postId);
                    }
                });
            } else {
                missedIds.add(postId);
            }
        }

        if (!missedIds.isEmpty()) {
            dbTimer.record(() -> loadFromDBAndCache(missedIds, result));
        }

        totalSample.stop(meterRegistry.timer("feed.metadata.total"));
        return result;
    }

    private void loadFromDBAndCache(List<Long> postIds, Map<Long, PostMetadata> result) {
        var posts = postRepository.findAllById(postIds);
        for (var post : posts) {
            PostMetadata metadata = PostMetadata.fromEntity(post);
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
            List<UserInterestScore> scores = userInterestScoreRepository.findByMemberId(memberId);
            for (UserInterestScore s : scores) interests.put(s.getCategory(), s.getScore());
        }
        return interests;
    }
}