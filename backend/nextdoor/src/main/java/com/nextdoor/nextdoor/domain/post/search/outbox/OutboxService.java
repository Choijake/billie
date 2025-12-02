package com.nextdoor.nextdoor.domain.post.search.outbox;

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
    private final RedisCoalescer coalescer;

    private final MeterRegistry meterRegistry;

    public List<Long> publishViewsAndCollectSuccessIds(List<OutboxEventDto> batch) {
        List<Long> ok = new ArrayList<>(batch.size());
        for (OutboxEventDto e : batch) {
            Timer.Sample t = Timer.start(meterRegistry);
            try {
                if ("DELETE".equals(e.getEventType())) {
                    sqsPublisher.sendDelete(e.getPayload()).join();
                    //성공건만 리스트에 담음
                    ok.add(e.getId());
                    t.stop(Timer.builder("outbox.process.event.delete")
                            .publishPercentileHistogram().register(meterRegistry));
                } else {
                    coalescer.put(e.getAggregateId(), e.getVersion(), e.getPayload());
                    //성공건만 리스트에 담음
                    ok.add(e.getId());
                    t.stop(Timer.builder("outbox.process.event.upsert")
                            .publishPercentileHistogram().register(meterRegistry));
                }
            } catch (Exception ex) {
                t.stop(Timer.builder("outbox.process.event.error")
                        .publishPercentileHistogram().register(meterRegistry));

                //실패하면 재시도
                meterRegistry.counter("outbox.process.event.failed").increment();
                log.warn("Outbox 이벤트 처리 실패 id={}", e.getId(), ex);
            }
        }
        return ok;
    }
}