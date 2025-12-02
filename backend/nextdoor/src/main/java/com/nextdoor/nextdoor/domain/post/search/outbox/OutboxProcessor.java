package com.nextdoor.nextdoor.domain.post.search.outbox;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionSystemException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

@Service
@Profile("outbox")
@RequiredArgsConstructor
@Slf4j
public class OutboxProcessor {

    private static final int BATCH_SIZE = 100;
    private static final int CLAIM_TTL_SEC = 120;

    private final OutboxClaimService claimService;
    private final OutboxService outboxService;
    private final MeterRegistry meterRegistry;

    private DistributionSummary batchSizeSummary;

    private String workerId;

    @PostConstruct
    public void init() {
        this.workerId = "outbox-" + UUID.randomUUID();
        this.batchSizeSummary = DistributionSummary.builder("outbox.process.batch_size")
                .description("Outbox 처리 배치 크기").baseUnit("messages")
                .publishPercentileHistogram().register(meterRegistry);

        // 지터
        try { Thread.sleep(ThreadLocalRandom.current().nextInt(100, 500)); }
        catch (InterruptedException ignored) {}
    }

    @Scheduled(fixedDelay = 2000)
    public void pollAndRoute() {
        Timer.Sample total = Timer.start(meterRegistry);
        try {
            // 클레임
            Timer.Sample claimT = Timer.start(meterRegistry);
            List<OutboxEventDto> views = claimService.claimBatch(workerId, BATCH_SIZE, CLAIM_TTL_SEC);
            claimT.stop(Timer.builder("outbox.process.step.claim")
                    .description("클레임 확정 단계").publishPercentileHistogram()
                    .register(meterRegistry));

            if (views.isEmpty()) return;
            batchSizeSummary.record(views.size());

            // 성공 ID만 수집
            Timer.Sample procT = Timer.start(meterRegistry);
            List<Long> okIds = outboxService.publishViewsAndCollectSuccessIds(views);
            procT.stop(Timer.builder("outbox.process.step.process")
                    .description("이벤트 처리 단계").publishPercentileHistogram()
                    .register(meterRegistry));

            if (okIds.isEmpty()) return;

            // 성공 ID만 IN 마킹 (데드락 재시도 + 1000개 청크)
            Timer.Sample markT = Timer.start(meterRegistry);
            int updated = markPublishedWithRetryInChunks(okIds, 1000);
            markT.stop(Timer.builder("outbox.process.step.mark")
                    .description("발행 마킹(IN) 단계").publishPercentileHistogram()
                    .register(meterRegistry));

            log.info("Outbox 처리 완료: batch={}, successMarked={}", views.size(), updated);

        } catch (Exception e) {
            meterRegistry.counter("outbox.process.fatal_error").increment();
            log.error("Outbox 처리 중 치명적 오류", e);
        } finally {
            total.stop(Timer.builder("outbox.process.total")
                    .description("배치 전체 시간").publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    private int markPublishedWithRetryInChunks(List<Long> okIds, int chunkSize) {
        int updated = 0;
        for (int i = 0; i < okIds.size(); i += chunkSize) {
            List<Long> chunk = okIds.subList(i, Math.min(i + chunkSize, okIds.size()));
            updated += retryDeadlock(() -> claimService.markPublishedIn(chunk));
        }
        return updated;
    }

    private <T> T retryDeadlock(Supplier<T> op) {
        int max = 3; long backoff = 50;
        for (int i = 0; i < max; i++) {
            try { return op.get(); }
            catch (TransactionSystemException | org.springframework.dao.DeadlockLoserDataAccessException e) {
                if (i < max - 1) {
                    try { Thread.sleep(backoff); } catch (InterruptedException ignored) {}
                    backoff *= 2;
                    continue;
                }
                throw e;
            }
        }
        throw new IllegalStateException("unreachable");
    }
}