package com.nextdoor.nextdoor.domain.search.outbox;

import com.nextdoor.nextdoor.domain.search.config.SearchProperties;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
@Profile("outbox")
@RequiredArgsConstructor
@Slf4j
public class SqsPublisher {

    private final SqsAsyncClient sqsClient;
    private final Jsons jsons;
    private final MeterRegistry meterRegistry;
    private final SearchProperties props;

    @Value("${sqs.queue.upsert}")
    private String upsertQueueUrl;

    @Value("${sqs.queue.delete}")
    private String deleteQueueUrl;

    /** 타입별 메트릭과 큐 URL을 묶은 컨텍스트 객체 — boolean 플래그 제거. (OCP) */
    private record SqsTarget(
            String queueUrl,
            String typeTag,
            DistributionSummary msgSize,
            DistributionSummary batchSize,
            Counter success,
            Counter failed
    ) {}

    private SqsTarget upsertTarget;
    private SqsTarget deleteTarget;

    @PostConstruct
    public void init() {
        upsertTarget = buildTarget(upsertQueueUrl, "UPSERT");
        deleteTarget = buildTarget(deleteQueueUrl, "DELETE");
    }

    private SqsTarget buildTarget(String queueUrl, String typeTag) {
        return new SqsTarget(
                queueUrl,
                typeTag,
                DistributionSummary.builder("sqs.message.bytes")
                        .description("SQS 메시지 크기").baseUnit("bytes")
                        .publishPercentileHistogram().tag("type", typeTag)
                        .register(meterRegistry),
                DistributionSummary.builder("sqs.batch.size")
                        .description("SQS 배치 크기").baseUnit("messages")
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

    public CompletableFuture<Void> sendUpsertBatch(List<String> payloads) {
        return sendBatch(payloads, upsertTarget, "UPS");
    }

    public CompletableFuture<Void> sendDeleteBatch(List<String> payloads) {
        return sendBatch(payloads, deleteTarget, "DEL");
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

    private CompletableFuture<Void> sendBatch(List<String> payloads, SqsTarget target, String dedupePrefix) {
        if (payloads == null || payloads.isEmpty()) return CompletableFuture.completedFuture(null);
        target.batchSize().record(payloads.size());
        payloads.forEach(p -> target.msgSize().record(p.getBytes(StandardCharsets.UTF_8).length));

        int batchLimit = props.getSqs().getBatchLimit();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < payloads.size(); i += batchLimit) {
            List<String> chunk = payloads.subList(i, Math.min(i + batchLimit, payloads.size()));
            futures.add(sendBatchInternal(chunk, target, dedupePrefix, 0));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    private CompletableFuture<Void> sendBatchInternal(
            List<String> chunk, SqsTarget target, String dedupePrefix, int attempt) {

        Timer.Sample t = Timer.start(meterRegistry);
        List<SendMessageBatchRequestEntry> entries = new ArrayList<>(chunk.size());

        try {
            for (int i = 0; i < chunk.size(); i++) {
                String payload = chunk.get(i);
                long postId = extractPostId(payload, dedupePrefix);
                long version = extractVersion(payload, dedupePrefix);
                entries.add(SendMessageBatchRequestEntry.builder()
                        .id("m" + i)
                        .messageBody(payload)
                        .messageGroupId(String.valueOf(postId))
                        .messageDeduplicationId(dedupe(postId, version, dedupePrefix))
                        .build());
            }

            return sqsClient.sendMessageBatch(b -> b.queueUrl(target.queueUrl()).entries(entries))
                    .thenCompose(resp -> {
                        int ok   = resp.successful() == null ? 0 : resp.successful().size();
                        int fail = resp.failed()     == null ? 0 : resp.failed().size();
                        target.success().increment(ok);
                        target.failed().increment(fail);

                        if (fail > 0 && attempt < 2) {
                            List<String> retryPayloads = new ArrayList<>(fail);
                            for (BatchResultErrorEntry e : resp.failed()) {
                                retryPayloads.add(chunk.get(Integer.parseInt(e.id().substring(1))));
                            }
                            try { Thread.sleep(Duration.ofMillis(100L * (1L << attempt)).toMillis()); }
                            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                            return sendBatchInternal(retryPayloads, target, dedupePrefix, attempt + 1);
                        }
                        return CompletableFuture.completedFuture(null);
                    })
                    .whenComplete((r, ex) -> stopBatchTimer(t, target.typeTag(), attempt));

        } catch (Exception e) {
            target.failed().increment(chunk.size());
            stopBatchTimer(t, target.typeTag(), attempt);
            log.error("SQS 배치 전송 중 예외 (type={}, attempt={}, size={}): {}",
                    target.typeTag(), attempt, chunk.size(), e.getMessage(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    private long extractPostId(String payload, String prefix) throws Exception {
        return "UPS".equals(prefix)
                ? jsons.toUpsert(payload).getPostId()
                : jsons.toDelete(payload).getPostId();
    }

    private long extractVersion(String payload, String prefix) throws Exception {
        return "UPS".equals(prefix)
                ? jsons.toUpsert(payload).getVersion()
                : jsons.toDelete(payload).getVersion();
    }

    private void stopSingleTimer(Timer.Sample t, String typeTag) {
        t.stop(Timer.builder("sqs.send.single")
                .description("SQS 단건 전송 시간")
                .publishPercentileHistogram()
                .tag("type", typeTag)
                .register(meterRegistry));
    }

    private void stopBatchTimer(Timer.Sample t, String typeTag, int attempt) {
        t.stop(Timer.builder("sqs.send.batch")
                .description("SQS 배치 전송 시간")
                .publishPercentileHistogram()
                .tag("type", typeTag)
                .tag("attempt", String.valueOf(attempt))
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
