package com.nextdoor.nextdoor.domain.post.search.outbox;

import com.nextdoor.nextdoor.domain.post.search.outbox.event.PostDeleteEvent;
import com.nextdoor.nextdoor.domain.post.search.outbox.event.PostUpsertEvent;
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

    private static final int SQS_BATCH_LIMIT = 10;

    private final SqsAsyncClient sqsClient;
    private final Jsons jsons;
    private final MeterRegistry meterRegistry;

    @Value("${sqs.queue.upsert}")
    private String upsertQueueUrl;

    @Value("${sqs.queue.delete}")
    private String deleteQueueUrl;

    private DistributionSummary upsertSize;
    private DistributionSummary deleteSize;
    private DistributionSummary upsertBatchSize;
    private DistributionSummary deleteBatchSize;

    private Counter upsertSuccess;
    private Counter upsertFailed;
    private Counter deleteSuccess;
    private Counter deleteFailed;

    @PostConstruct
    public void init() {
        this.upsertSize = DistributionSummary.builder("sqs.message.bytes")
                .description("SQS 메시지 크기").baseUnit("bytes")
                .publishPercentileHistogram().tag("type", "UPSERT")
                .register(meterRegistry);

        this.deleteSize = DistributionSummary.builder("sqs.message.bytes")
                .description("SQS 메시지 크기").baseUnit("bytes")
                .publishPercentileHistogram().tag("type", "DELETE")
                .register(meterRegistry);

        this.upsertBatchSize = DistributionSummary.builder("sqs.batch.size")
                .description("SQS 배치 크기").baseUnit("messages")
                .publishPercentileHistogram().tag("type", "UPSERT")
                .register(meterRegistry);

        this.deleteBatchSize = DistributionSummary.builder("sqs.batch.size")
                .description("SQS 배치 크기").baseUnit("messages")
                .publishPercentileHistogram().tag("type", "DELETE")
                .register(meterRegistry);

        this.upsertSuccess = Counter.builder("sqs.send.success")
                .description("SQS 전송 성공 건수").tag("type", "UPSERT")
                .register(meterRegistry);

        this.upsertFailed = Counter.builder("sqs.send.failed")
                .description("SQS 전송 실패 건수").tag("type", "UPSERT")
                .register(meterRegistry);

        this.deleteSuccess = Counter.builder("sqs.send.success")
                .description("SQS 전송 성공 건수").tag("type", "DELETE")
                .register(meterRegistry);

        this.deleteFailed = Counter.builder("sqs.send.failed")
                .description("SQS 전송 실패 건수").tag("type", "DELETE")
                .register(meterRegistry);
    }

    public CompletableFuture<SendMessageResponse> sendDelete(String payload) {
        Timer.Sample t = Timer.start(meterRegistry);
        try {
            PostDeleteEvent ev = jsons.toDelete(payload);
            deleteSize.record(payload.getBytes(StandardCharsets.UTF_8).length);
            SendMessageRequest req = SendMessageRequest.builder()
                    .queueUrl(deleteQueueUrl)
                    .messageGroupId(String.valueOf(ev.getPostId()))
                    .messageDeduplicationId(dedupe(ev.getPostId(), ev.getVersion(), "DEL"))
                    .messageBody(payload)
                    .build();
            return sqsClient.sendMessage(req)
                    .whenComplete((resp, ex) -> {
                        if (ex == null) deleteSuccess.increment();
                        else deleteFailed.increment();
                        t.stop(Timer.builder("sqs.send.single")
                                .description("SQS 단건 전송 시간")
                                .publishPercentileHistogram()
                                .tag("type", "DELETE")
                                .register(meterRegistry));
                    });
        } catch (Exception e) {
            deleteFailed.increment();
            t.stop(Timer.builder("sqs.send.single")
                    .description("SQS 단건 전송 시간")
                    .publishPercentileHistogram()
                    .tag("type", "DELETE")
                    .register(meterRegistry));
            log.error("삭제 메시지 전송 중 오류 발생: {}", e.getMessage(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<SendMessageResponse> sendUpsert(String payload) {
        Timer.Sample t = Timer.start(meterRegistry);
        try {
            PostUpsertEvent ev = jsons.toUpsert(payload);
            upsertSize.record(payload.getBytes(StandardCharsets.UTF_8).length);
            SendMessageRequest req = SendMessageRequest.builder()
                    .queueUrl(upsertQueueUrl)
                    .messageGroupId(String.valueOf(ev.getPostId()))
                    .messageDeduplicationId(dedupe(ev.getPostId(), ev.getVersion(), "UPS"))
                    .messageBody(payload)
                    .build();
            return sqsClient.sendMessage(req)
                    .whenComplete((resp, ex) -> {
                        if (ex == null) upsertSuccess.increment();
                        else upsertFailed.increment();
                        t.stop(Timer.builder("sqs.send.single")
                                .description("SQS 단건 전송 시간")
                                .publishPercentileHistogram()
                                .tag("type", "UPSERT")
                                .register(meterRegistry));
                    });
        } catch (Exception e) {
            upsertFailed.increment();
            t.stop(Timer.builder("sqs.send.single")
                    .description("SQS 단건 전송 시간")
                    .publishPercentileHistogram()
                    .tag("type", "UPSERT")
                    .register(meterRegistry));
            log.error("업서트 메시지 전송 중 오류 발생: {}", e.getMessage(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<Void> sendUpsertBatch(List<String> payloads) {
        if (payloads == null || payloads.isEmpty()) return CompletableFuture.completedFuture(null);
        upsertBatchSize.record(payloads.size());
        payloads.forEach(p -> upsertSize.record(p.getBytes(StandardCharsets.UTF_8).length));

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < payloads.size(); i += SQS_BATCH_LIMIT) {
            List<String> chunk = payloads.subList(i, Math.min(i + SQS_BATCH_LIMIT, payloads.size()));
            futures.add(sendBatchInternal(chunk, true, 0));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    public CompletableFuture<Void> sendDeleteBatch(List<String> payloads) {
        if (payloads == null || payloads.isEmpty()) return CompletableFuture.completedFuture(null);
        deleteBatchSize.record(payloads.size());
        payloads.forEach(p -> deleteSize.record(p.getBytes(StandardCharsets.UTF_8).length));

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < payloads.size(); i += SQS_BATCH_LIMIT) {
            List<String> chunk = payloads.subList(i, Math.min(i + SQS_BATCH_LIMIT, payloads.size()));
            futures.add(sendBatchInternal(chunk, false, 0));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    private CompletableFuture<Void> sendBatchInternal(List<String> chunk, boolean upsert, int attempt) {
        Timer.Sample t = Timer.start(meterRegistry);
        List<SendMessageBatchRequestEntry> entries = new ArrayList<>(chunk.size());

        try {
            for (int i = 0; i < chunk.size(); i++) {
                String payload = chunk.get(i);
                String id = "m" + i;

                String groupId;
                String dedupId;
                if (upsert) {
                    PostUpsertEvent ev = jsons.toUpsert(payload);
                    groupId = String.valueOf(ev.getPostId());
                    dedupId = dedupe(ev.getPostId(), ev.getVersion(), "UPS");
                } else {
                    PostDeleteEvent ev = jsons.toDelete(payload);
                    groupId = String.valueOf(ev.getPostId());
                    dedupId = dedupe(ev.getPostId(), ev.getVersion(), "DEL");
                }

                SendMessageBatchRequestEntry.Builder eb = SendMessageBatchRequestEntry.builder()
                        .id(id)
                        .messageBody(payload)
                        .messageGroupId(groupId)
                        .messageDeduplicationId(dedupId);
                entries.add(eb.build());
            }

            String queueUrl = upsert ? upsertQueueUrl : deleteQueueUrl;
            return sqsClient.sendMessageBatch(b -> b.queueUrl(queueUrl).entries(entries))
                    .thenCompose(resp -> {
                        int ok = resp.successful() == null ? 0 : resp.successful().size();
                        int fail = resp.failed() == null ? 0 : resp.failed().size();
                        if (upsert) {
                            upsertSuccess.increment(ok);
                            upsertFailed.increment(fail);
                        } else {
                            deleteSuccess.increment(ok);
                            deleteFailed.increment(fail);
                        }

                        if (fail > 0 && attempt < 2) {
                            List<String> retryPayloads = new ArrayList<>(fail);
                            for (BatchResultErrorEntry e : resp.failed()) {
                                int idx = Integer.parseInt(e.id().substring(1));
                                retryPayloads.add(chunk.get(idx));
                            }
                            try { Thread.sleep(Duration.ofMillis(100L * (1L << attempt)).toMillis()); }
                            catch (InterruptedException ignored) {}
                            return sendBatchInternal(retryPayloads, upsert, attempt + 1);
                        }
                        return CompletableFuture.completedFuture(null);
                    })
                    .whenComplete((r, ex) -> {
                        t.stop(Timer.builder("sqs.send.batch")
                                .description("SQS 배치 전송 시간")
                                .publishPercentileHistogram()
                                .tag("type", upsert ? "UPSERT" : "DELETE")
                                .tag("attempt", String.valueOf(attempt))
                                .register(meterRegistry));
                    });

        } catch (Exception e) {
            if (upsert) upsertFailed.increment(chunk.size());
            else deleteFailed.increment(chunk.size());
            t.stop(Timer.builder("sqs.send.batch")
                    .description("SQS 배치 전송 시간")
                    .publishPercentileHistogram()
                    .tag("type", upsert ? "UPSERT" : "DELETE")
                    .tag("attempt", String.valueOf(attempt))
                    .register(meterRegistry));
            log.error("SQS 배치 전송 중 예외 (type={}, attempt={}, size={}): {}",
                    upsert ? "UPSERT" : "DELETE", attempt, chunk.size(), e.getMessage(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    private String dedupe(Long id, Long v, String t) {
        return t + ":" + id + ":" + v;
    }
}