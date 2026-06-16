package com.nextdoor.nextdoor.domain.search.outbox;

import io.micrometer.core.instrument.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
@Profile("outbox")
@RequiredArgsConstructor
@Slf4j
public class OutboxService {
    private final SqsPublisher sqsPublisher;
    private final MeterRegistry meterRegistry;

    public List<Long> publishViewsAndCollectSuccessIds(List<OutboxEventDto> batch) {
        ConcurrentLinkedQueue<Long> ok = new ConcurrentLinkedQueue<>();
        Map<Long, List<OutboxEventDto>> groups = new LinkedHashMap<>();
        for (OutboxEventDto e : batch) {
            groups.computeIfAbsent(e.getAggregateId(), ignored -> new ArrayList<>()).add(e);
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>(groups.size());
        for (List<OutboxEventDto> events : groups.values()) {
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (OutboxEventDto e : events) {
                chain = chain.thenCompose(ignored -> publishOne(e, ok));
            }
            futures.add(chain);
        }

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        return new ArrayList<>(ok);
    }

    private CompletableFuture<Void> publishOne(OutboxEventDto e, ConcurrentLinkedQueue<Long> ok) {
        Timer.Sample t = Timer.start(meterRegistry);
        OutboxEventType type = OutboxEventType.from(e.getEventType());
        return sqsPublisher.send(e.getPayload())
                .thenAccept(resp -> {
                    t.stop(Timer.builder("outbox.process.event." + type.name().toLowerCase())
                            .publishPercentileHistogram().register(meterRegistry));
                    ok.add(e.getId());
                })
                .exceptionally(ex -> {
                    t.stop(Timer.builder("outbox.process.event.error")
                            .publishPercentileHistogram().register(meterRegistry));
                    meterRegistry.counter("outbox.process.event.failed").increment();
                    log.warn("SQS 발행 실패 id={}, aggregateId={}, type={}",
                            e.getId(), e.getAggregateId(), e.getEventType(), ex);
                    return null;
                });
    }
}
