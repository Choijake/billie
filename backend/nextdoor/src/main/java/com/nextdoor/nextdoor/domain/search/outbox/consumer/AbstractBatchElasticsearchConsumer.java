package com.nextdoor.nextdoor.domain.search.outbox.consumer;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.nextdoor.nextdoor.domain.search.config.SearchProperties;
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Template Method 패턴 — Upsert/Delete 컨슈머의 공통 flush/buffer/scheduling 로직. (DRY)
 * 서브클래스는 buildOperation()과 operationName()만 구현하면 된다.
 */
@Slf4j
public abstract class AbstractBatchElasticsearchConsumer {

    protected final ElasticsearchAsyncClient es;
    protected final MeterRegistry meterRegistry;
    protected final String indexName;
    private final int maxConcurrency;
    private final int sliceMaxActions;

    protected static class Pending {
        final BulkOperation op;
        final Acknowledgement ack;
        Pending(BulkOperation op, Acknowledgement ack) { this.op = op; this.ack = ack; }
    }

    private final List<Pending> buffer = new CopyOnWriteArrayList<>();

    protected AbstractBatchElasticsearchConsumer(ElasticsearchAsyncClient es,
                                                  MeterRegistry meterRegistry,
                                                  SearchProperties props) {
        this.es = es;
        this.meterRegistry = meterRegistry;
        this.indexName = props.getIndexName();
        this.maxConcurrency = props.getBulk().getMaxConcurrency();
        this.sliceMaxActions = props.getBulk().getSliceMaxActions();
    }

    // ──── Template Methods ───────────────────────────────────────────────────

    /** 메시지 문자열로부터 ES BulkOperation을 생성한다. */
    protected abstract BulkOperation buildOperation(String msg) throws Exception;

    /** 메트릭 이름 및 로그에 사용할 작업명 ("upsert" | "delete") */
    protected abstract String operationName();

    // ──── Shared Logic ───────────────────────────────────────────────────────

    public void process(String msg, Acknowledgement ack) {
        try {
            BulkOperation op = buildOperation(msg);
            buffer.add(new Pending(op, ack));
            if (buffer.size() >= sliceMaxActions) {
                flush();
            }
        } catch (Exception ex) {
            log.error("{} 메시지 처리 중 오류: {}", operationName(), msg, ex);
            // ACK 안 함 → SQS 재전송
        }
    }

    protected void periodicFlushInternal() {
        if (!buffer.isEmpty()) flush();
    }

    protected synchronized void flush() {
        if (buffer.isEmpty()) return;

        List<Pending> pendings = new ArrayList<>(buffer);
        buffer.clear();

        log.info("{} 작업 {}건 플러시 시작", operationName(), pendings.size());

        Timer.Sample t = Timer.start(meterRegistry);
        try {
            List<List<Pending>> slices = sliceByCount(pendings, sliceMaxActions);
            int idx = 0;

            while (idx < slices.size()) {
                List<CompletableFuture<Void>> window = new ArrayList<>(maxConcurrency);

                for (int k = 0; k < maxConcurrency && idx < slices.size(); k++, idx++) {
                    List<Pending> slice = slices.get(idx);
                    List<BulkOperation> ops = slice.stream().map(p -> p.op).toList();

                    CompletableFuture<Void> f = es.bulk(b -> b.index(indexName).operations(ops))
                            .thenAccept(resp -> {
                                if (Boolean.TRUE.equals(resp.errors())) {
                                    List<BulkResponseItem> items = resp.items();
                                    for (int i = 0; i < items.size(); i++) {
                                        var it = items.get(i);
                                        if (it.error() == null) {
                                            slice.get(i).ack.acknowledge();
                                        } else {
                                            log.warn("ES {} 실패 idx={} type={} reason={}",
                                                    operationName(), i, it.error().type(), it.error().reason());
                                        }
                                    }
                                } else {
                                    slice.forEach(p -> p.ack.acknowledge());
                                }
                                log.debug("{} 슬라이스 완료 size={}, errors={}",
                                        operationName(), slice.size(), resp.errors());
                            })
                            .exceptionally(ex -> {
                                log.warn("{} 슬라이스 실패 size={}", operationName(), slice.size(), ex);
                                return null;
                            });

                    window.add(f);
                }

                CompletableFuture.allOf(window.toArray(CompletableFuture[]::new)).join();
            }

            log.info("{} 플러시 완료: {}건", operationName(), pendings.size());
        } finally {
            t.stop(Timer.builder("indexer.es.bulk." + operationName())
                    .description("ES " + operationName() + " bulk 병렬 플러시 시간")
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    private List<List<Pending>> sliceByCount(List<Pending> list, int max) {
        List<List<Pending>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += max) {
            out.add(list.subList(i, Math.min(i + max, list.size())));
        }
        return out;
    }

    @PreDestroy
    public void onShutdown() {
        try { flush(); } catch (Exception e) {
            log.warn("{} 종료 플러시 중 예외", operationName(), e);
        }
    }
}
