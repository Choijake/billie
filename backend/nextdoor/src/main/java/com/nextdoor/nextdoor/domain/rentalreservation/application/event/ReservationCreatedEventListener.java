package com.nextdoor.nextdoor.domain.rentalreservation.application.event;

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
public class ReservationCreatedEventListener {

    private final ActionService actionService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleReservationCreated(ReservationCreatedEvent event) {
        log.info("[Event] 예약 확정 후 Redis 점수 반영 시작: memberId={}, category={}", 
                 event.getMemberId(), event.getCategory());
                 
        try {
            actionService.logInteraction(
                event.getMemberId(), 
                event.getCategory(), 
                ActionType.RESERVE
            );
        } catch (Exception e) {
            log.error("Redis 점수 반영 실패", e);
        }
    }
}