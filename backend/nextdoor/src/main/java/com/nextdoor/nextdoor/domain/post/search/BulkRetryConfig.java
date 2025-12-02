package com.nextdoor.nextdoor.domain.post.search;

import java.time.Duration;

public record BulkRetryConfig(int maxAttempts, Duration backoffBase, Duration backoffMax, Duration futureTimeout) {

    public static BulkRetryConfig defaults() {
        return new BulkRetryConfig(4, Duration.ofMillis(500), Duration.ofSeconds(8), Duration.ofSeconds(60));
    }
}