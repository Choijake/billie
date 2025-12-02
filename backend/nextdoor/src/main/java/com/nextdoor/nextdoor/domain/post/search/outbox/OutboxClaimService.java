package com.nextdoor.nextdoor.domain.post.search.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@Profile("outbox")
@RequiredArgsConstructor
public class OutboxClaimService {

    private final OutboxEventRepository repo;
    private final MeterRegistry meterRegistry;

    @Transactional(timeout = 5)
    public List<OutboxEventDto> claimBatch(String workerId, int batch, int ttlSec) {
        Timer.Sample t = Timer.start(meterRegistry);
        try {
            List<Long> ids = repo.selectClaimableIds(ttlSec, batch);
            if (ids.isEmpty()) return List.of();
            repo.markClaimed(workerId, ids);
            return repo.findViewsByIds(ids);
        } finally {
            t.stop(Timer.builder("outbox.claim.duration")
                    .description("Outbox 클레임 전체 시간").publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    @Transactional(timeout = 5)
    public int markPublishedIn(List<Long> okIds) {
        return repo.markPublishedIn(okIds);
    }
}