package com.nextdoor.nextdoor.domain.search.reconciliation;

import com.nextdoor.nextdoor.domain.search.config.SearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * TimeWindowReconciler 정기 실행 스케줄러.
 *
 * search.reconciliation.enabled=true일 때만 활성화.
 * 벤치마크 테스트는 이 빈 없이 TimeWindowReconciler를 직접 호출한다.
 */
@Component
@Profile("worker")
@ConditionalOnProperty(name = "search.reconciliation.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class ReconciliationScheduler {

    private final TimeWindowReconciler reconciler;
    private final SearchProperties props;

    @Scheduled(fixedDelayString = "${search.reconciliation.interval-ms:600000}")
    public void run() {
        try {
            reconciler.reconcile(props.getReconciliation().getWindowMinutes());
        } catch (Exception e) {
            log.error("Reconciliation 스케줄 실행 실패", e);
        }
    }
}
