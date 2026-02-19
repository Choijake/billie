package com.nextdoor.nextdoor.common;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreaker feedRedisCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("feedRedis");
    }
}