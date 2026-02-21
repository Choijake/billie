package com.nextdoor.nextdoor.domain.feed.domain.service;

import com.nextdoor.nextdoor.domain.feed.application.service.dto.PostMetadata;
import com.nextdoor.nextdoor.domain.post.domain.Category;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.Map;

/**
 * 기본 점수 계산 전략
 * - 관심사 가점
 * - 최신성 가점
 * - 기본 가중치 적용
 */
@Component
public class DefaultPostScorer implements PostScorer {

    private static final double BASE_SCORE = 1.0;
    private static final double INTEREST_WEIGHT = 0.5;
    private static final double RECENCY_WEIGHT = 0.3;
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @Override
    public double calculateScore(PostMetadata metadata,
                                 Map<Category, Long> userInterests) {

        double score = BASE_SCORE;

        // 관심사 가중치
        if (metadata.category() != null) {
            Long interestScore = userInterests.get(metadata.category());
            if (interestScore != null) {
                score += INTEREST_WEIGHT * Math.log(1 + interestScore);
            }
        }

        // 최신성 가중치
        if (metadata.createdAt() != null) {

            long epochMillis = metadata.createdAt()
                    .atZone(ZONE)
                    .toInstant()
                    .toEpochMilli();

            long ageSeconds =
                    (System.currentTimeMillis() - epochMillis) / 1000;

            // 하루 기준으로 자연 감소
            double recencyScore = Math.exp(-ageSeconds / 86400.0);
            score += RECENCY_WEIGHT * recencyScore;
        }

        return score;
    }
}