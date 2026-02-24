package com.nextdoor.nextdoor.domain.feed.infrastructure.redis;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class RedisCallExecutor {

    private final CircuitBreaker feedRedisGeoCircuitBreaker;
    private final CircuitBreaker feedRedisSessionCircuitBreaker;
    private final CircuitBreaker feedRedisMetadataCircuitBreaker;
    private final CircuitBreaker feedRedisInterestCircuitBreaker;

    public RedisCallExecutor(
            @Qualifier("feedRedisGeoCircuitBreaker") CircuitBreaker feedRedisGeoCircuitBreaker,
            @Qualifier("feedRedisSessionCircuitBreaker") CircuitBreaker feedRedisSessionCircuitBreaker,
            @Qualifier("feedRedisMetadataCircuitBreaker") CircuitBreaker feedRedisMetadataCircuitBreaker,
            @Qualifier("feedRedisInterestCircuitBreaker") CircuitBreaker feedRedisInterestCircuitBreaker
    ) {
        this.feedRedisGeoCircuitBreaker = feedRedisGeoCircuitBreaker;
        this.feedRedisSessionCircuitBreaker = feedRedisSessionCircuitBreaker;
        this.feedRedisMetadataCircuitBreaker = feedRedisMetadataCircuitBreaker;
        this.feedRedisInterestCircuitBreaker = feedRedisInterestCircuitBreaker;
    }

    public <T> T call(String op, Supplier<T> supplier) {
        Supplier<T> decorated = CircuitBreaker.decorateSupplier(resolveCircuitBreaker(op), supplier);
        return decorated.get();
    }

    public void run(String op, Runnable runnable) {
        Runnable decorated = CircuitBreaker.decorateRunnable(resolveCircuitBreaker(op), runnable);
        decorated.run();
    }

    private CircuitBreaker resolveCircuitBreaker(String op) {
        return switch (RedisOpType.fromOp(op)) {
            case GEO -> feedRedisGeoCircuitBreaker;
            case METADATA -> feedRedisMetadataCircuitBreaker;
            case INTEREST -> feedRedisInterestCircuitBreaker;
            case SESSION -> feedRedisSessionCircuitBreaker;
        };
    }
}
