package com.nextdoor.nextdoor.domain.feed.infrastructure.redis;

import com.nextdoor.nextdoor.domain.feed.infrastructure.exception.RedisInfraException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class RedisExecution {

    private final RedisCallExecutor executor;

    public <T> T failFast(String op, Supplier<T> supplier) {
        try {
            return executor.call(op, supplier);
        } catch (Exception e) {
            throw new RedisInfraException("[Redis Fail] op=" + op, e);
        }
    }

    public void failFastRun(String op, Runnable runnable) {
        try {
            executor.run(op, runnable);
        } catch (Exception e) {
            throw new RedisInfraException("[Redis Fail] op=" + op, e);
        }
    }
}