package com.nextdoor.nextdoor.domain.search.outbox;

import io.micrometer.core.instrument.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

@Service
@Profile("outbox")
@RequiredArgsConstructor
@Slf4j
public class OutboxService {
    private final SqsPublisher sqsPublisher;
    private final EventCoalescer coalescer;

    private final MeterRegistry meterRegistry;

    public List<Long> publishViewsAndCollectSuccessIds(List<OutboxEventDto> batch) {
        List<Long> ok = new ArrayList<>(batch.size());
        for (OutboxEventDto e : batch) {
            Timer.Sample t = Timer.start(meterRegistry);
            try {
                OutboxEventType type = OutboxEventType.from(e.getEventType());
                if (type == OutboxEventType.DELETE) {
                    sqsPublisher.sendDelete(e.getPayload()).join();
                    ok.add(e.getId());
                    t.stop(Timer.builder("outbox.process.event.delete")
                            .publishPercentileHistogram().register(meterRegistry));
                } else {
                    coalescer.put(e.getAggregateId(), e.getVersion(), e.getPayload());
                    ok.add(e.getId());
                    t.stop(Timer.builder("outbox.process.event.upsert")
                            .publishPercentileHistogram().register(meterRegistry));
                }
            } catch (Exception ex) {
                t.stop(Timer.builder("outbox.process.event.error")
                        .publishPercentileHistogram().register(meterRegistry));

                meterRegistry.counter("outbox.process.event.failed").increment();
                log.warn("Outbox 이벤트 처리 실패 id={}", e.getId(), ex);
            }
        }
        return ok;
    }
}