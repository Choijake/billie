package com.nextdoor.nextdoor.common;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreaker feedRedisGeoCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("feedRedisGeo");
    }

    @Bean
    public CircuitBreaker feedRedisSessionCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("feedRedisSession");
    }

    @Bean
    public CircuitBreaker feedRedisMetadataCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("feedRedisMetadata");
    }

    @Bean
    public CircuitBreaker feedRedisInterestCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("feedRedisInterest");
    }

    @Bean
    public CircuitBreaker feedEsFallbackCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("feedEsFallback");
    }

    @Bean
    public Bulkhead feedEsFallbackBulkhead(BulkheadRegistry registry) {
        return registry.bulkhead("feedEsFallback");
    }

    @Bean
    public RateLimiter feedEsFallbackRateLimiter(RateLimiterRegistry registry) {
        return registry.rateLimiter("feedEsFallback");
    }
}
