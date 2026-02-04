package com.nextdoor.nextdoor.domain.feed.application.service;

import com.nextdoor.nextdoor.domain.post.domain.Category;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;


@Slf4j
@Service
@RequiredArgsConstructor
public class ActionService {

    private final RedisTemplate<String, Object> dataRedisTemplate;

    // 행동별 가중치
    private static final long VIEW_WEIGHT = 1L;
    private static final long LIKE_WEIGHT = 3L;
    private static final long RESERVE_WEIGHT = 10L;


    public void logInteraction(Long memberId, Category category, ActionType actionType) {
        String key = generateUserInterestKey(memberId);
        long weight = getActionWeight(actionType);

        try {
            dataRedisTemplate.opsForHash().increment(key, category.name(), weight);

            log.debug("[Action Logged] memberId={}, category={}, action={}, weight={}",
                    memberId, category, actionType, weight);

        } catch (Exception e) {
            log.error("[Action Logging Failed] memberId={}, category={}, error={}",
                    memberId, category, e.getMessage());
        }
    }

    /**
     * 행동 타입별 가중치 반환
     */
    private long getActionWeight(ActionType actionType) {
        return switch (actionType) {
            case VIEW -> VIEW_WEIGHT;
            case LIKE -> LIKE_WEIGHT;
            case RESERVE -> RESERVE_WEIGHT;
        };
    }

    /**
     * Redis Key 생성
     */
    private String generateUserInterestKey(Long memberId) {
        return String.format("user:%d:interest", memberId);
    }

    /**
     * 행동 타입 Enum
     */
    public enum ActionType {
        VIEW,
        LIKE,
        RESERVE
    }
}