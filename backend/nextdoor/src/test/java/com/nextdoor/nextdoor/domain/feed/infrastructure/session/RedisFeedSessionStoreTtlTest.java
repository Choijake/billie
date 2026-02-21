package com.nextdoor.nextdoor.domain.feed.infrastructure.session;

import com.nextdoor.nextdoor.domain.feed.application.port.FeedSessionStore;
import com.nextdoor.nextdoor.domain.feed.infrastructure.redis.RedisCallExecutor;
import com.nextdoor.nextdoor.domain.feed.infrastructure.redis.RedisExecution;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

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

        CircuitBreaker cb = CircuitBreaker.ofDefaults("feedRedis");
        RedisCallExecutor callExecutor = new RedisCallExecutor(cb);
        RedisExecution redisExecution = new RedisExecution(callExecutor);

        // 고정된 세션 ID를 사용해 결정적인 dataKey 생성
        SessionIdGenerator idGenerator = new SessionIdGenerator() {
            @Override
            public String nextId() {
                return "fixed-session";
            }
        };
        keyFactory = new TimestampSessionKeyFactory(idGenerator);

        sessionStore = new RedisFeedSessionStoreAdapter(redisTemplate, redisExecution, keyFactory);

        // 테스트 시작 전에 Redis 비우기
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