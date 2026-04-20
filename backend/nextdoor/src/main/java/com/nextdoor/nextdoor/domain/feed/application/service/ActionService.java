package com.nextdoor.nextdoor.domain.feed.application.service;

import com.nextdoor.nextdoor.domain.feed.infrastructure.persistence.UserInterestScoreRepository;
import com.nextdoor.nextdoor.domain.post.domain.Category;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ActionService {

    private final UserInterestScoreRepository scoreRepository;

    private static final int VIEW_WEIGHT    = 10;
    private static final int LIKE_WEIGHT    = 30;
    private static final int RESERVE_WEIGHT = 100;

    @Transactional
    public void logInteraction(Long memberId, Category category, ActionType actionType) {
        int weight = getActionWeight(actionType);
        scoreRepository.upsertScore(memberId, category.name(), weight);
        log.debug("[Action Logged] memberId={}, category={}, action={}, weight={}",
                memberId, category, actionType, weight);
    }

    private int getActionWeight(ActionType actionType) {
        return switch (actionType) {
            case VIEW    -> VIEW_WEIGHT;
            case LIKE    -> LIKE_WEIGHT;
            case RESERVE -> RESERVE_WEIGHT;
        };
    }

    public enum ActionType {
        VIEW,
        LIKE,
        RESERVE
    }
}
