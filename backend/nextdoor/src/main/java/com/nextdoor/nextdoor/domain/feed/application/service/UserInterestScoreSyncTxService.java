package com.nextdoor.nextdoor.domain.feed.application.service;

import com.nextdoor.nextdoor.domain.feed.domain.UserInterestScore;
import com.nextdoor.nextdoor.domain.feed.infrastructure.persistence.UserInterestScoreRepository;
import com.nextdoor.nextdoor.domain.post.domain.Category;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserInterestScoreSyncTxService {

    private final UserInterestScoreRepository scoreRepository;

    @Transactional
    public void syncScores(Long memberId, Map<Object, Object> scores) {
        for (Map.Entry<Object, Object> entry : scores.entrySet()) {
            String categoryName = String.valueOf(entry.getKey());
            if ("EMPTY".equals(categoryName)) continue;

            try {
                Category category = Category.valueOf(categoryName);
                Long score = parseScore(entry.getValue());
                if (score == null) {
                    log.warn("[UPSERT Skipped] memberId={}, category={}, reason=invalid_score", memberId, categoryName);
                    continue;
                }

                UserInterestScore entity = scoreRepository
                        .findByMemberIdAndCategory(memberId, category)
                        .orElse(UserInterestScore.builder()
                                .memberId(memberId)
                                .category(category)
                                .score(0L)
                                .build());

                entity.updateScore(score);
                scoreRepository.save(entity);

            } catch (IllegalArgumentException e) {
                log.warn("[UPSERT Skipped] memberId={}, category={}, reason=unknown_category", memberId, categoryName);
            } catch (Exception e) {
                log.error("[UPSERT Failed] memberId={}, category={}", memberId, categoryName, e);
            }
        }
    }

    private Long parseScore(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (Exception ignored) {
            return null;
        }
    }
}
