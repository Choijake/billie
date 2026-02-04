package com.nextdoor.nextdoor.domain.post.event;

import com.nextdoor.nextdoor.domain.feed.application.service.ActionService;
import com.nextdoor.nextdoor.domain.feed.application.service.ActionService.ActionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class PostLikedEventListener {

    private final ActionService actionService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePostLiked(PostLikedEvent event) {
        log.info("[Event] 게시물 좋아요 후 Redis 점수 반영 시작: memberId={}, category={}", 
                 event.getMemberId(), event.getCategory());
                 
        try {
            actionService.logInteraction(
                event.getMemberId(), 
                event.getCategory(), 
                ActionType.LIKE
            );
        } catch (Exception e) {
            log.error("Redis 점수 반영 실패", e);
        }
    }
}