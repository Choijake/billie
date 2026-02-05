package com.nextdoor.nextdoor.domain.feed.domain.service;

import com.nextdoor.nextdoor.domain.feed.application.service.dto.PostMetadata;
import com.nextdoor.nextdoor.domain.post.domain.Category;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Random;

@Component
public class PostScorer {
    private static final Random random = new Random();

    public double calculateScore(PostMetadata post, Map<Category, Long> userInterests) {
        double recency = calculateRecencyScore(post.createdAt());
        double interest = calculateInterestScore(post.category(), userInterests);
        double noise = random.nextDouble() * 5;

        return (recency * 0.3) + (interest * 0.5) + (noise * 0.2);
    }

    private double calculateRecencyScore(LocalDateTime createdAt) {
        if (createdAt == null) return 10.0;
        long daysAgo = Duration.between(createdAt, LocalDateTime.now()).toDays();
        if (daysAgo <= 1) return 100.0;
        if (daysAgo <= 7) return 70.0;
        if (daysAgo <= 30) return 40.0;
        return 10.0;
    }

    private double calculateInterestScore(Category category, Map<Category, Long> userInterests) {
        long maxInterest = userInterests.values().stream().max(Long::compare).orElse(1L);
        long rawInterest = userInterests.getOrDefault(category, 0L);
        return (rawInterest * 100.0) / maxInterest;
    }
}