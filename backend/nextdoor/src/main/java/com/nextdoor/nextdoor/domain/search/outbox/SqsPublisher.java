package com.nextdoor.nextdoor.domain.search.outbox;

import com.nextdoor.nextdoor.domain.search.outbox.event.PostDeleteEvent;
import com.nextdoor.nextdoor.domain.search.outbox.event.PostUpsertEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

@Service
@Profile("outbox")
@RequiredArgsConstructor
@Slf4j
public class SqsPublisher {

    private final SqsAsyncClient sqsClient;
    private final Jsons jsons;
    private final MeterRegistry meterRegistry;

    @Value("${sqs.queue.post-index}")
    private String postIndexQueueUrl;

    /** 타입별 메트릭과 큐 URL을 묶은 컨텍스트 객체 — boolean 플래그 제거. (OCP) */
    private record SqsTarget(
            String queueUrl,
            String typeTag,
            DistributionSummary msgSize,
            Counter success,
            Counter failed
    ) {}

    private SqsTarget upsertTarget;
    private SqsTarget deleteTarget;

    @PostConstruct
    public void init() {
        upsertTarget = buildTarget(postIndexQueueUrl, "UPSERT");
        deleteTarget = buildTarget(postIndexQueueUrl, "DELETE");
    }

    private SqsTarget buildTarget(String queueUrl, String typeTag) {
        return new SqsTarget(
                queueUrl,
                typeTag,
                DistributionSummary.builder("sqs.message.bytes")
                        .description("SQS 메시지 크기").baseUnit("bytes")
                        .publishPercentileHistogram().tag("type", typeTag)
                        .register(meterRegistry),
                Counter.builder("sqs.send.success")
                        .description("SQS 전송 성공 건수").tag("type", typeTag)
                        .register(meterRegistry),
                Counter.builder("sqs.send.failed")
                        .description("SQS 전송 실패 건수").tag("type", typeTag)
                        .register(meterRegistry)
        );
    }

    // ──── Public API ─────────────────────────────────────────────────────────

    public CompletableFuture<SendMessageResponse> send(String payload) {
        return switch (jsons.readEventType(payload)) {
            case DELETE -> sendDelete(payload);
            case UPSERT -> sendUpsert(payload);
        };
    }

    public CompletableFuture<SendMessageResponse> sendDelete(String payload) {
        return sendSingle(payload, deleteTarget, () -> {
            PostDeleteEvent ev = jsons.toDelete(payload);
            return SendMessageRequest.builder()
                    .queueUrl(deleteTarget.queueUrl())
                    .messageGroupId(String.valueOf(ev.getPostId()))
                    .messageDeduplicationId(dedupe(ev.getPostId(), ev.getVersion(), "DEL"))
                    .messageBody(payload)
                    .build();
        });
    }

    public CompletableFuture<SendMessageResponse> sendUpsert(String payload) {
        return sendSingle(payload, upsertTarget, () -> {
            PostUpsertEvent ev = jsons.toUpsert(payload);
            return SendMessageRequest.builder()
                    .queueUrl(upsertTarget.queueUrl())
                    .messageGroupId(String.valueOf(ev.getPostId()))
                    .messageDeduplicationId(dedupe(ev.getPostId(), ev.getVersion(), "UPS"))
                    .messageBody(payload)
                    .build();
        });
    }

    // ──── Private Helpers ────────────────────────────────────────────────────

    private CompletableFuture<SendMessageResponse> sendSingle(
            String payload, SqsTarget target, RequestSupplier supplier) {

        Timer.Sample t = Timer.start(meterRegistry);
        try {
            target.msgSize().record(payload.getBytes(StandardCharsets.UTF_8).length);
            SendMessageRequest req = supplier.get();
            return sqsClient.sendMessage(req)
                    .whenComplete((resp, ex) -> {
                        if (ex == null) target.success().increment();
                        else target.failed().increment();
                        stopSingleTimer(t, target.typeTag());
                    });
        } catch (Exception e) {
            target.failed().increment();
            stopSingleTimer(t, target.typeTag());
            log.error("{} 단건 전송 중 오류: {}", target.typeTag(), e.getMessage(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    private void stopSingleTimer(Timer.Sample t, String typeTag) {
        t.stop(Timer.builder("sqs.send.single")
                .description("SQS 단건 전송 시간")
                .publishPercentileHistogram()
                .tag("type", typeTag)
                .register(meterRegistry));
    }

    private String dedupe(long id, long v, String prefix) {
        return prefix + ":" + id + ":" + v;
    }

    @FunctionalInterface
    private interface RequestSupplier {
        SendMessageRequest get() throws Exception;
    }
}
