package com.nextdoor.nextdoor.domain.post.service;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Post 생성/수정 관련 Micrometer 지표 보관소.
 * 비즈니스 로직(PostCommandService)에서 계측 코드를 격리한다. (SRP)
 */
@Component
@RequiredArgsConstructor
public class PostMetrics {

    private final MeterRegistry meterRegistry;

    private Timer dbSaveTimer;
    private Timer outboxInsertTimer;
    private Timer imageUploadOneTimer;
    private Timer imageUploadAllTimer;
    private Timer totalCreateTimer;
    private DistributionSummary imageCountSummary;
    private DistributionSummary imageBytesSummary;

    @PostConstruct
    public void init() {
        dbSaveTimer = Timer.builder("post.create.phase")
                .tag("phase", "db.save")
                .publishPercentileHistogram()
                .register(meterRegistry);

        outboxInsertTimer = Timer.builder("outbox.insert.latency")
                .description("Outbox 저장 구간")
                .tag("aggregate", "post")
                .publishPercentileHistogram()
                .register(meterRegistry);

        imageUploadOneTimer = Timer.builder("post.create.phase")
                .tag("phase", "image.upload.one")
                .publishPercentileHistogram()
                .register(meterRegistry);

        imageUploadAllTimer = Timer.builder("post.create.phase")
                .tag("phase", "image.upload.all")
                .publishPercentileHistogram()
                .register(meterRegistry);

        totalCreateTimer = Timer.builder("post.create.total")
                .publishPercentileHistogram()
                .register(meterRegistry);

        imageCountSummary = DistributionSummary.builder("post.images.count")
                .register(meterRegistry);

        imageBytesSummary = DistributionSummary.builder("post.images.bytes")
                .register(meterRegistry);
    }

    public Timer.Sample startSample() {
        return Timer.start(meterRegistry);
    }

    public void stopDbSave(Timer.Sample sample)         { sample.stop(dbSaveTimer); }
    public void stopOutboxInsert(Timer.Sample sample)   { sample.stop(outboxInsertTimer); }
    public void stopImageUploadOne(Timer.Sample sample) { sample.stop(imageUploadOneTimer); }
    public void stopImageUploadAll(Timer.Sample sample) { sample.stop(imageUploadAllTimer); }
    public void stopTotalCreate(Timer.Sample sample)    { sample.stop(totalCreateTimer); }

    public void recordImageCount(int count)   { imageCountSummary.record(count); }
    public void recordImageBytes(long bytes)  { imageBytesSummary.record(bytes); }
    public void incrementImageUploadError()   { meterRegistry.counter("post.images.upload.error").increment(); }
}
