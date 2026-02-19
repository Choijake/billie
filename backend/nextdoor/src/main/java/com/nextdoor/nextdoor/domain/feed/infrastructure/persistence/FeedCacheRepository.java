package com.nextdoor.nextdoor.domain.feed.infrastructure.persistence;

import ch.hsr.geohash.GeoHash;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextdoor.nextdoor.domain.feed.application.service.dto.PostMetadata;
import com.nextdoor.nextdoor.domain.feed.domain.Exception.FeedRedisUnavailableException;
import com.nextdoor.nextdoor.domain.post.domain.Category;
import com.nextdoor.nextdoor.domain.post.repository.PostRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
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
    private final MeterRegistry meterRegistry;
    private final RedisCallExecutor redisCallExecutor;
    private static final String GEO_KEY_PREFIX = "feed:geo:";
    private static final String SESSION_PTR_PREFIX = "session:ptr:";
    private static final String SESSION_DATA_PREFIX = "session:data:";
    private static final int GEOHASH_PRECISION = 5;

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

    // 세션 존재 확인
    public boolean hasFeedSession(Long memberId) {
        String pointerKey = SESSION_PTR_PREFIX + memberId;
        return redisFailFast("hasFeedSession", () -> Boolean.TRUE.equals(redisTemplate.hasKey(pointerKey)));
    }

    // 위치 기반 조회
    public List<Long> findNearbyPostIds(Double lat, Double lon, double radiusKm, int globalLimit) {
        Timer.Sample totalSample = Timer.start(meterRegistry);

        try {
            return redisFailFast("findNearbyPostIds", () -> {
                Point center = new Point(lon, lat);
                Distance radius = new Distance(radiusKm, Metrics.KILOMETERS);
                Circle circle = new Circle(center, radius);

                List<String> targetKeys = getTargetGeohashKeys(lat, lon);
                int perKeyLimit = (int) Math.ceil((double) globalLimit / targetKeys.size()) + 20;

                Timer.Sample redisSample = Timer.start(meterRegistry);
                List<Object> pipelineResults = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                    for (String key : targetKeys) {
                        connection.geoCommands().geoRadius(
                                key.getBytes(),
                                circle,
                                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                        .includeDistance()
                                        .limit(perKeyLimit)
                        );
                    }
                    return null;
                });
                redisSample.stop(meterRegistry.timer("feed.nearby.step", "step", "redis_geo"));

                if (pipelineResults == null) return Collections.emptyList();

                Timer.Sample streamSample = Timer.start(meterRegistry);
                List<Long> results = pipelineResults.stream()
                        .filter(Objects::nonNull)
                        .map(obj -> (GeoResults<RedisGeoCommands.GeoLocation<byte[]>>) obj)
                        .flatMap(geoResults -> geoResults.getContent().stream())
                        .sorted(Comparator.comparingDouble(res -> res.getDistance().getValue()))
                        .map(res -> Long.parseLong(new String(res.getContent().getName())))
                        .distinct()
                        .limit(globalLimit)
                        .collect(Collectors.toList());
                streamSample.stop(meterRegistry.timer("feed.nearby.step", "step", "stream_process"));

                return results;
            });
        } finally {
            totalSample.stop(meterRegistry.timer("feed.nearby.total"));
        }
    }

    // 위치 인덱싱
    public void addGeoLocation(Long postId, Double lat, Double lon) {
        String key = getGeohashKey(lat, lon);
        redisBestEffort("addGeoLocation", () ->
                redisTemplate.opsForGeo().add(key, new Point(lon, lat), String.valueOf(postId))
        );
    }

    // 세션 저장
    public void saveFeedSession(Long memberId, List<Long> postIds, Duration ttl) {
        if (postIds.isEmpty()) return;

        redisFailFastRun("saveFeedSession", () -> {
            long timestamp = System.currentTimeMillis();
            String pointerKey = SESSION_PTR_PREFIX + memberId;
            String newDataKey = SESSION_DATA_PREFIX + memberId + ":" + timestamp;

            Set<ZSetOperations.TypedTuple<String>> tuples = new HashSet<>();
            for (int i = 0; i < postIds.size(); i++) {
                tuples.add(new DefaultTypedTuple<>(String.valueOf(postIds.get(i)), (double) i));
            }

            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                byte[] dataKeyBytes = newDataKey.getBytes();
                byte[] ptrKeyBytes = pointerKey.getBytes();
                byte[] versionBytes = newDataKey.getBytes();

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

    // 세션 조회
    public List<Long> getFeedSessionPage(Long memberId, int page, int size) {
        return redisFailFast("getFeedSessionPage", () -> {
            String pointerKey = SESSION_PTR_PREFIX + memberId;

            String dataKey = redisTemplate.opsForValue().get(pointerKey);
            if (dataKey == null) return Collections.emptyList();

            long start = (long) page * size;
            long end = start + size - 1;

            Set<String> ids = redisTemplate.opsForZSet().range(dataKey, start, end);
            if (ids == null || ids.isEmpty()) return Collections.emptyList();

            return ids.stream().map(Long::parseLong).toList();
        });
    }

    // 메타데이터 조회
    public Map<Long, PostMetadata> getPostMetadata(List<Long> postIds) {
        return redisFailFast("getPostMetadata", () -> {
            Timer.Sample totalSample = Timer.start(meterRegistry);

            try {
                Timer redisTimer = meterRegistry.timer("feed.metadata.step", "step", "redis_get");
                Timer jsonTimer = meterRegistry.timer("feed.metadata.step", "step", "json_parse");
                Timer dbTimer = meterRegistry.timer("feed.metadata.step", "step", "db_fallback");

                Map<Long, PostMetadata> result = new HashMap<>();
                List<String> keys = postIds.stream().map(id -> "post:" + id + ":info").toList();

                List<String> jsonList = redisTimer.record(() -> redisTemplate.opsForValue().multiGet(keys));
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

                return result;
            } finally {
                totalSample.stop(meterRegistry.timer("feed.metadata.total"));
            }
        });
    }

    private void loadFromDBAndCache(List<Long> postIds, Map<Long, PostMetadata> result) {
        List<PostMetadata> missedMetadataList = new ArrayList<>();

        var posts = postRepository.findAllById(postIds);
        for (var post : posts) {
            PostMetadata metadata = PostMetadata.fromEntity(post);
            result.put(post.getId(), metadata);
            missedMetadataList.add(metadata);
        }

        if (!missedMetadataList.isEmpty()) {
            redisBestEffort("cachePostMetadata", () -> {
                redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                    for (PostMetadata metadata : missedMetadataList) {
                        try {
                            String key = "post:" + metadata.postId() + ":info";
                            String json = objectMapper.writeValueAsString(metadata);
                            connection.stringCommands().setEx(key.getBytes(), 3600, json.getBytes());
                        } catch (JsonProcessingException e) {
                            log.error("JSON Serialization Error", e);
                        }
                    }
                    return null;
                });
            });
        }
    }

    // 관심사 조회
    public Map<Category, Long> getUserInterests(Long memberId) {
        if (redisCallExecutor.isOpen()) {
            return Collections.emptyMap();
        }

        try {
            return redisCallExecutor.call(() -> {
                Timer.Sample totalSample = Timer.start(meterRegistry);

                try {
                    String key = "user:" + memberId + ":interest";
                    Map<Category, Long> interests = new HashMap<>();

                    Timer.Sample redisReadSample = Timer.start(meterRegistry);
                    Map<Object, Object> redisMap = redisTemplate.opsForHash().entries(key);
                    redisReadSample.stop(meterRegistry.timer("feed.interests.step", "step", "redis_read"));

                    if (redisMap.containsKey("EMPTY")) {
                        totalSample.stop(meterRegistry.timer("feed.interests.total", "result", "hit_empty"));
                        return interests;
                    }

                    if (!redisMap.isEmpty()) {
                        redisMap.forEach((k, v) -> {
                            try {
                                interests.put(Category.valueOf((String) k), Long.parseLong((String) v));
                            } catch (Exception ignored) {}
                        });
                        totalSample.stop(meterRegistry.timer("feed.interests.total", "result", "hit_data"));
                        return interests;
                    }

                    totalSample.stop(meterRegistry.timer("feed.interests.total", "result", "miss_skip"));
                    return interests;

                } finally {

                }
            });
        } catch (Exception e) {
            return Collections.emptyMap();
        }
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
}