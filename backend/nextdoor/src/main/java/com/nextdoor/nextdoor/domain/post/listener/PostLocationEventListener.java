package com.nextdoor.nextdoor.domain.post.listener;

import com.nextdoor.nextdoor.domain.feed.application.service.FeedService;
import com.nextdoor.nextdoor.domain.post.event.PostLocationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class PostLocationEventListener {

    private final FeedService feedService;

    /**
     * 트랜잭션 커밋 후 Redis Geo에 위치 정보 저장
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePostLocationEvent(PostLocationEvent event) {
        try {
            feedService.addGeoLocation(event.getPostId(), event.getLatitude(), event.getLongitude());
            log.debug("Redis Geo 위치 정보 저장 완료: postId={}, lat={}, lon={}", 
                    event.getPostId(), event.getLatitude(), event.getLongitude());
        } catch (Exception e) {
            log.error("Redis Geo 위치 정보 저장 실패: postId={}, error={}", event.getPostId(), e.getMessage());
        }
    }
}