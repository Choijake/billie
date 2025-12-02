package com.nextdoor.nextdoor.domain.post.search;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BulkRetryExecutor {

    private static final int TARGET_SLICE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_SLICE_BYTES = 15 * 1024 * 1024;

    private final BulkTransport transport;
    private final ScheduledExecutorService scheduler;
    private final BulkRetryConfig cfg;

    public BulkRetryExecutor(ElasticsearchAsyncClient asyncEsClient) {
        this(new EsBulkTransport(asyncEsClient), null, BulkRetryConfig.defaults());
    }

    public BulkRetryExecutor(BulkTransport transport, ScheduledExecutorService scheduler, BulkRetryConfig cfg) {
        this.transport = transport;
        this.scheduler = scheduler;
        this.cfg = (cfg == null) ? BulkRetryConfig.defaults() : cfg;
    }

    public static final class Slice {
        public final List<BulkOperation> ops;
        public final int approxBytes;

        public Slice(List<BulkOperation> ops, int approxBytes) {
            this.ops = ops;
            this.approxBytes = approxBytes;
        }
    }

    public List<Slice> sliceBySize(List<BulkOperation> ops) {
        List<Slice> out = new ArrayList<>();
        List<BulkOperation> buf = new ArrayList<>();
        int bytes = 0;
        for (BulkOperation op : ops) {
            int sz = estimateBytes(op);
            if (!buf.isEmpty() && (bytes + sz) > MAX_SLICE_BYTES) {
                out.add(new Slice(List.copyOf(buf), bytes));
                buf.clear();
                bytes = 0;
            }
            buf.add(op);
            bytes += sz;
            if (bytes >= TARGET_SLICE_BYTES) {
                out.add(new Slice(List.copyOf(buf), bytes));
                buf.clear();
                bytes = 0;
            }
        }
        if (!buf.isEmpty()) {
            out.add(new Slice(List.copyOf(buf), bytes));
        }
        return out;
    }

    public CompletableFuture<Void> sendSliceWithRetry(List<BulkOperation> ops) {
        return executeWithRetry(new ArrayList<>(ops), 0);
    }

    public CompletableFuture<Void> sendSliceWithRetry(Slice slice) {
        return sendSliceWithRetry(slice.ops);
    }

    private CompletableFuture<Void> executeWithRetry(List<BulkOperation> pendingOperations, int attemptCount) {
        if (attemptCount >= cfg.maxAttempts()) {
            String errorMessage = String.format("최대 재시도 횟수(%d) 초과. 남은 항목=%d",
                    cfg.maxAttempts(), pendingOperations.size());
            return createFailedFuture(errorMessage, null);
        }

        return transport.send(pendingOperations)
                .orTimeout(cfg.futureTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .handle((response, exception) -> {
                    if (exception != null) {
                        // 네트워크/타임아웃
                        return handleTransportFailure(pendingOperations, attemptCount, exception);
                    }

                    if (!response.errors()) {
                        // 모든 작업이 성공한 경우
                        return CompletableFuture.completedFuture(null);
                    }

                    // 개별 아이템 실패 처리
                    return handleItemFailures(response, pendingOperations, attemptCount);
                })
                .thenCompose(future -> (CompletableFuture<Void>) future);
    }

    private CompletableFuture<Void> handleTransportFailure(List<BulkOperation> pendingOperations,
                                                           int attemptCount,
                                                           Throwable exception) {
        long delayMs = calculateBackoffDelay(attemptCount + 1);
        return delay(delayMs)
                .thenCompose(ignored -> executeWithRetry(pendingOperations, attemptCount + 1));
    }

    private CompletableFuture<Void> handleItemFailures(co.elastic.clients.elasticsearch.core.BulkResponse response,
                                                       List<BulkOperation> originalOperations,
                                                       int attemptCount) {
        List<BulkOperation> retryableOperations = new ArrayList<>();

        var responseItems = response.items();
        for (int i = 0; i < responseItems.size(); i++) {
            var item = responseItems.get(i);

            if (item.error() == null) {
                continue; // 성공한 아이템은 건너뜀
            }

            ErrorClassification classification = classifyError(item.status(), item.error().type());

            switch (classification) {
                case BENIGN:
                    // 무해한 오류 (404, 409)
                    break;

                case RETRYABLE:
                    // 재시도 가능한 오류 (429, 5xx)
                    retryableOperations.add(originalOperations.get(i));
                    break;

                case FATAL:
                    // 치명적 오류는 즉시 실패 처리
                    String errorMessage = String.format("재시도 불가한 오류: status=%d, type=%s, reason=%s",
                            item.status(),
                            item.error().type(),
                            item.error().reason());
                    return createFailedFuture(errorMessage, null);
            }
        }

        if (retryableOperations.isEmpty()) {
            // 재시도할 항목이 없으면 성공 처리
            return CompletableFuture.completedFuture(null);
        }

        // 재시도 가능한 항목들에 대해 재귀 호출
        long delayMs = calculateBackoffDelay(attemptCount + 1);
        return delay(delayMs)
                .thenCompose(ignored -> executeWithRetry(retryableOperations, attemptCount + 1));
    }

    private ErrorClassification classifyError(int statusCode, String errorType) {
        // 무해한 오류들
        if (statusCode == 404 || statusCode == 409 || "version_conflict_engine_exception".equals(errorType)) {
            return ErrorClassification.BENIGN;
        }

        // 재시도 가능한 오류들
        if (statusCode == 429 || statusCode >= 500) {
            return ErrorClassification.RETRYABLE;
        }

        // 나머지는 치명적 오류로 분류
        return ErrorClassification.FATAL;
    }

    private long calculateBackoffDelay(int attemptNumber) {
        long baseDelayMs = (long) Math.pow(2, attemptNumber) * cfg.backoffBase().toMillis();
        return Math.min(baseDelayMs, cfg.backoffMax().toMillis());
    }

    private CompletableFuture<Void> delay(long delayMs) {
        if (scheduler == null) {
            // 스케줄러가 없는 경우 블로킹 방식으로 대기
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(null);
        }

        // 논블로킹 방식으로 대기
        var future = new CompletableFuture<Void>();
        scheduler.schedule(() -> future.complete(null), delayMs, TimeUnit.MILLISECONDS);
        return future;
    }

    private CompletableFuture<Void> createFailedFuture(String errorMessage, Throwable cause) {
        var future = new CompletableFuture<Void>();
        RuntimeException exception = cause != null
                ? new RuntimeException(errorMessage, cause)
                : new RuntimeException(errorMessage);
        future.completeExceptionally(exception);
        return future;
    }

    private static int estimateBytes(BulkOperation op) {
        try {
            int jsonBytes = op.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            return Math.min(Math.max(jsonBytes, 1024), 256 * 1024) + 128;
        } catch (Exception e) {
            return 8 * 1024;
        }
    }

    private enum ErrorClassification {
        BENIGN,     // 무해한 오류
        RETRYABLE,  // 재시도 가능한 오류
        FATAL       // 치명적 오류
    }
}