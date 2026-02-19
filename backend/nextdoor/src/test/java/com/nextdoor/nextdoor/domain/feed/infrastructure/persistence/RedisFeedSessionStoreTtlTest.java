package com.nextdoor.nextdoor.domain.feed.infrastructure.persistence;

import com.nextdoor.nextdoor.domain.feed.application.port.FeedSessionStore;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisFeedSessionStoreTtlTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private StringRedisTemplate redisTemplate;
    private FeedSessionStore sessionStore;
    private FeedSessionKeyFactory keyFactory;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory cf =
                new LettuceConnectionFactory(redis.getHost(), redis.getFirstMappedPort());
        cf.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate();
        redisTemplate.setConnectionFactory(cf);
        redisTemplate.afterPropertiesSet();

        // CircuitBreaker는 테스트에서 항상 CLOSED로 두면 됨
        CircuitBreaker cb = CircuitBreaker.ofDefaults("feedRedis");
        RedisCallExecutor redisCallExecutor = new RedisCallExecutor(cb);

        // Clock은 고정(결정적 키)으로 두는 게 테스트에 유리
        Clock fixedClock = Clock.systemUTC();
        keyFactory = new TimestampSessionKeyFactory(fixedClock);

        sessionStore = new RedisFeedSessionStore(redisTemplate, redisCallExecutor, keyFactory);

        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    void touchSession_shouldIncreasePTTL_forPointerAndDataKey() throws Exception {
        Long memberId = 1L;
        Duration ttl = Duration.ofSeconds(5);

        // 1) 세션 저장
        sessionStore.saveSession(memberId, List.of(1L, 2L, 3L, 4L, 5L), ttl);

        String pointerKey = keyFactory.pointerKey(memberId);
        String dataKey = redisTemplate.opsForValue().get(pointerKey);

        assertThat(dataKey).isNotBlank();

        // 2) 최초 TTL 확인
        Long pttl1Ptr = redisTemplate.getExpire(pointerKey, TimeUnit.MILLISECONDS);
        Long pttl1Data = redisTemplate.getExpire(dataKey, TimeUnit.MILLISECONDS);

        assertThat(pttl1Ptr).isGreaterThan(0);
        assertThat(pttl1Data).isGreaterThan(0);

        // 3) 시간 경과로 TTL 감소
        Thread.sleep(1500);

        Long pttl2Ptr = redisTemplate.getExpire(pointerKey, TimeUnit.MILLISECONDS);
        Long pttl2Data = redisTemplate.getExpire(dataKey, TimeUnit.MILLISECONDS);

        assertThat(pttl2Ptr).isLessThan(pttl1Ptr);
        assertThat(pttl2Data).isLessThan(pttl1Data);

        // 4) touch -> TTL 슬라이딩 연장
        sessionStore.touchSession(memberId, ttl);

        Long pttl3Ptr = redisTemplate.getExpire(pointerKey, TimeUnit.MILLISECONDS);
        Long pttl3Data = redisTemplate.getExpire(dataKey, TimeUnit.MILLISECONDS);

        // touch 이후 남은 TTL이 증가해야 함
        assertThat(pttl3Ptr).isGreaterThan(pttl2Ptr);
        assertThat(pttl3Data).isGreaterThan(pttl2Data);
    }
}