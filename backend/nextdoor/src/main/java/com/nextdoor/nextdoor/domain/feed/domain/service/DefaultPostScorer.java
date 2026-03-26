package com.nextdoor.nextdoor.domain.feed.domain.service;

import com.nextdoor.nextdoor.domain.feed.application.FeedConfig;
import com.nextdoor.nextdoor.domain.feed.application.service.dto.PostMetadata;
import com.nextdoor.nextdoor.domain.post.domain.Category;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.Map;

@Component
public class DefaultPostScorer implements PostScorer {

    private static final double BASE_SCORE = 1.0;
    private static final double INTEREST_WEIGHT = 0.5;
    private static final double RECENCY_WEIGHT = 0.3;
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final long decayConstantSeconds;

    public DefaultPostScorer(FeedConfig feedConfig) {
        this.decayConstantSeconds = feedConfig.decayConstantSeconds();
    }

    @Override
    public double calculateScore(PostMetadata metadata,
                                 Map<Category, Long> userInterests) {

        double score = BASE_SCORE;

        if (metadata.category() != null) {
            Long interestScore = userInterests.get(metadata.category());
            if (interestScore != null) {
                score += INTEREST_WEIGHT * Math.log(1 + interestScore);
            }
        }

        if (metadata.createdAt() != null) {
            long epochMillis = metadata.createdAt()
                    .atZone(ZONE)
                    .toInstant()
                    .toEpochMilli();

            long ageSeconds =
                    (System.currentTimeMillis() - epochMillis) / 1000;

            double recencyScore = Math.exp(-ageSeconds / (double) decayConstantSeconds);
            score += RECENCY_WEIGHT * recencyScore;
        }

        return score;
    }
}
