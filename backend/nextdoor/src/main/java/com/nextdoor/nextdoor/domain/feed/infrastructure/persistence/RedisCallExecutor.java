package com.nextdoor.nextdoor.domain.feed.infrastructure.persistence;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class RedisCallExecutor {

    private final CircuitBreaker feedRedisCircuitBreaker;

    public <T> T call(Supplier<T> supplier) {
        Supplier<T> decorated = CircuitBreaker.decorateSupplier(feedRedisCircuitBreaker, supplier);
        return decorated.get();
    }

    public void run(Runnable runnable) {
        Runnable decorated = CircuitBreaker.decorateRunnable(feedRedisCircuitBreaker, runnable);
        decorated.run();
    }

    public boolean isOpen() {
        return feedRedisCircuitBreaker.getState() == CircuitBreaker.State.OPEN;
    }

    public CircuitBreaker.State state() {
        return feedRedisCircuitBreaker.getState();
    }

    public boolean isCallNotPermitted(Throwable t) {
        return t instanceof CallNotPermittedException;
    }
}
