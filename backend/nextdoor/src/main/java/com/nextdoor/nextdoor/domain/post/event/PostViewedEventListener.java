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
public class PostViewedEventListener {

    private final ActionService actionService;

    // DB 트랜잭션이 성공(Commit)한 후에만 실행됨
    @Async // 메인 로직 응답 속도에 영향을 주지 않기 위해 비동기 처리
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePostViewed(PostViewedEvent event) {
        log.info("[Event] 게시물 조회 후 Redis 점수 반영 시작: memberId={}, category={}", 
                 event.getMemberId(), event.getCategory());
                 
        try {
            // Redis 점수 +1 (VIEW)
            actionService.logInteraction(
                event.getMemberId(), 
                event.getCategory(), 
                ActionType.VIEW
            );
        } catch (Exception e) {
            // Redis 업데이트 실패가 조회 로직 전체를 망치면 안되므로 로그만 남김
            log.error("Redis 점수 반영 실패", e);
        }
    }
}