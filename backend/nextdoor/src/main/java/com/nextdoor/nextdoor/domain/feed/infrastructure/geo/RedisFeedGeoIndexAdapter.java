package com.nextdoor.nextdoor.domain.feed.infrastructure.geo;

import ch.hsr.geohash.GeoHash;
import com.nextdoor.nextdoor.domain.feed.application.port.FeedGeoIndexPort;
import com.nextdoor.nextdoor.domain.feed.domain.GeoPoint;
import com.nextdoor.nextdoor.domain.feed.infrastructure.redis.RedisExecution;
import lombok.RequiredArgsConstructor;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class RedisFeedGeoIndexAdapter implements FeedGeoIndexPort {

    private final StringRedisTemplate redisTemplate;
    private final RedisExecution redis;

    private static final String GEO_KEY_PREFIX = "feed:geo:";
    private static final String GEO_POST_MAP_PREFIX = "feed:geo:post:";
    private static final int GEOHASH_PRECISION = 5;
    private static final Duration GEO_POST_MAP_TTL = Duration.ofDays(7);

    @Override
    public List<Long> findNearbyPostIds(GeoPoint center, double radiusKm, int globalLimit) {
        return redis.failFast("geo.findNearbyPostIds", () -> {
            Point redisCenter = new Point(center.lon(), center.lat());
            Distance radius = new Distance(radiusKm, Metrics.KILOMETERS);
            Circle circle = new Circle(redisCenter, radius);

            List<String> targetKeys = getTargetGeohashKeys(center.lat(), center.lon());
            int perKeyLimit = (int) Math.ceil((double) globalLimit / targetKeys.size()) + 20;

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

            if (pipelineResults == null) return Collections.emptyList();

            return pipelineResults.stream()
                    .filter(Objects::nonNull)
                    .map(obj -> (GeoResults<RedisGeoCommands.GeoLocation<byte[]>>) obj)
                    .flatMap(geoResults -> geoResults.getContent().stream())
                    .sorted(Comparator.comparingDouble(res -> res.getDistance().getValue()))
                    .map(res -> Long.parseLong(new String(res.getContent().getName())))
                    .distinct()
                    .limit(globalLimit)
                    .collect(Collectors.toList());
        });
    }

    @Override
    public void addGeoLocation(Long postId, GeoPoint point) {
        redis.failFastRun("geo.addGeoLocation", () -> {
            String geoKey = geohashKey(point.lat(), point.lon());
            redisTemplate.opsForGeo().add(geoKey, new Point(point.lon(), point.lat()), String.valueOf(postId));
            redisTemplate.opsForValue().set(GEO_POST_MAP_PREFIX + postId, geoKey, GEO_POST_MAP_TTL);
        });
    }

    @Override
    public void removeGeoLocation(Long postId) {
        redis.failFastRun("geo.removeGeoLocation", () -> {
            String mapKey = GEO_POST_MAP_PREFIX + postId;
            String geoKey = redisTemplate.opsForValue().get(mapKey);
            if (geoKey == null || geoKey.isBlank()) return;

            redisTemplate.opsForZSet().remove(geoKey, String.valueOf(postId));
            redisTemplate.delete(mapKey);
        });
    }

    private String geohashKey(double lat, double lon) {
        GeoHash hash = GeoHash.withCharacterPrecision(lat, lon, GEOHASH_PRECISION);
        return GEO_KEY_PREFIX + hash.toBase32();
    }

    private List<String> getTargetGeohashKeys(double lat, double lon) {
        GeoHash centerHash = GeoHash.withCharacterPrecision(lat, lon, GEOHASH_PRECISION);
        List<String> keys = new ArrayList<>();
        keys.add(GEO_KEY_PREFIX + centerHash.toBase32());
        for (GeoHash neighbor : centerHash.getAdjacent()) {
            keys.add(GEO_KEY_PREFIX + neighbor.toBase32());
        }
        return keys;
    }
}