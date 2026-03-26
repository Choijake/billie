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

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@Testcontainers(disabledWithoutDocker = true)
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

        RedisExecution redisExecution = new RedisExecution(createRedisCallExecutor());

        SessionIdGenerator idGenerator = () -> "fixed-session";
        keyFactory = new TimestampSessionKeyFactory(idGenerator);

        sessionStore = new RedisFeedSessionStoreAdapter(
                redisTemplate, redisExecution, keyFactory, Clock.systemUTC()
        );

        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    void touchSession_shouldIncreasePTTL_forPointerAndDataKey() throws Exception {
        // given
        Long memberId = 1L;
        Duration ttl = Duration.ofSeconds(5);

        // when: 세션 저장
        sessionStore.saveSession(memberId, List.of(1L, 2L, 3L, 4L, 5L), ttl);

        String pointerKey = keyFactory.pointerKey(memberId);
        String dataKey = redisTemplate.opsForValue().get(pointerKey);

        assertThat(dataKey).isNotBlank();

        // then: 최초 TTL 확인
        Long pttl1Ptr = redisTemplate.getExpire(pointerKey, TimeUnit.MILLISECONDS);
        Long pttl1Data = redisTemplate.getExpire(dataKey, TimeUnit.MILLISECONDS);

        assertSoftly(softly -> {
            softly.assertThat(pttl1Ptr).isGreaterThan(0);
            softly.assertThat(pttl1Data).isGreaterThan(0);
        });

        // 시간 경과로 TTL 감소
        Thread.sleep(1500);

        Long pttl2Ptr = redisTemplate.getExpire(pointerKey, TimeUnit.MILLISECONDS);
        Long pttl2Data = redisTemplate.getExpire(dataKey, TimeUnit.MILLISECONDS);

        assertSoftly(softly -> {
            softly.assertThat(pttl2Ptr).isLessThan(pttl1Ptr);
            softly.assertThat(pttl2Data).isLessThan(pttl1Data);
        });

        // touch -> TTL 슬라이딩 연장 (dataKey 직접 전달)
        sessionStore.touchSession(dataKey, ttl);

        Long pttl3Ptr = redisTemplate.getExpire(pointerKey, TimeUnit.MILLISECONDS);
        Long pttl3Data = redisTemplate.getExpire(dataKey, TimeUnit.MILLISECONDS);

        assertSoftly(softly -> {
            softly.assertThat(pttl3Ptr).isGreaterThan(pttl2Ptr);
            softly.assertThat(pttl3Data).isGreaterThan(pttl2Data);
        });
    }

    private RedisCallExecutor createRedisCallExecutor() {
        CircuitBreaker geo = CircuitBreaker.ofDefaults("feedRedisGeo");
        CircuitBreaker session = CircuitBreaker.ofDefaults("feedRedisSession");
        CircuitBreaker metadata = CircuitBreaker.ofDefaults("feedRedisMetadata");
        CircuitBreaker interest = CircuitBreaker.ofDefaults("feedRedisInterest");
        return new RedisCallExecutor(geo, session, metadata, interest);
    }
}
