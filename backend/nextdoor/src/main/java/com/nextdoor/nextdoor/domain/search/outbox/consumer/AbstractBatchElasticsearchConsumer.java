package com.nextdoor.nextdoor.domain.search.outbox.consumer;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.nextdoor.nextdoor.domain.search.config.SearchProperties;
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

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
        final long aggregateId;
        final long version;
        Pending(BulkOperation op, Acknowledgement ack, long aggregateId, long version) {
            this.op = op; this.ack = ack; this.aggregateId = aggregateId; this.version = version;
        }
    }

    protected record OperationWithMeta(BulkOperation op, long aggregateId, long version) {}

    private final List<Pending> buffer = new CopyOnWriteArrayList<>();
    private final Counter versionConflictCounter;
    private final Counter bulkBufferReceivedCounter;
    private final Counter bulkBufferAfterDedupCounter;

    protected AbstractBatchElasticsearchConsumer(ElasticsearchAsyncClient es,
                                                  MeterRegistry meterRegistry,
                                                  SearchProperties props) {
        this.es = es;
        this.meterRegistry = meterRegistry;
        this.indexName = props.getIndexName();
        this.maxConcurrency = props.getBulk().getMaxConcurrency();
        this.sliceMaxActions = props.getBulk().getSliceMaxActions();
        this.versionConflictCounter = Counter.builder("indexer.es.bulk.version_conflict")
                .description("ES bulk version_conflict_engine_exception 횟수")
                .register(meterRegistry);
        this.bulkBufferReceivedCounter = Counter.builder("indexer.bulk.buffer.received")
                .description("벌크 버퍼에 들어온 총 메시지 수")
                .register(meterRegistry);
        this.bulkBufferAfterDedupCounter = Counter.builder("indexer.bulk.buffer.after_dedup")
                .description("중복 제거 후 ES에 전송된 문서 수")
                .register(meterRegistry);
    }

    protected abstract OperationWithMeta buildOperationWithMeta(String msg) throws Exception;

    protected abstract String operationName();

    public void process(String msg, Acknowledgement ack) {
        try {
            OperationWithMeta owm = buildOperationWithMeta(msg);
            buffer.add(new Pending(owm.op(), ack, owm.aggregateId(), owm.version()));
            if (buffer.size() >= sliceMaxActions) {
                flush();
            }
        } catch (Exception ex) {
            log.error("{} 메시지 처리 중 오류: {}", operationName(), msg, ex);
        }
    }

    protected void periodicFlushInternal() {
        if (!buffer.isEmpty()) flush();
    }

    protected synchronized void flush() {
        if (buffer.isEmpty()) return;

        List<Pending> raw = new ArrayList<>(buffer);
        buffer.clear();

        bulkBufferReceivedCounter.increment(raw.size());

        Map<Long, Pending> latest = new LinkedHashMap<>();
        for (Pending p : raw) {
            Pending existing = latest.get(p.aggregateId);
            if (existing == null || p.version > existing.version) {
                if (existing != null) {
                    existing.ack.acknowledge();
                }
                latest.put(p.aggregateId, p);
            } else {
                p.ack.acknowledge();
            }
        }
        List<Pending> pendings = new ArrayList<>(latest.values());
        bulkBufferAfterDedupCounter.increment(pendings.size());

        log.info("{} 작업 {}건 플러시 시작 (원본 {}건, dedup {}건 제거)",
                operationName(), pendings.size(), raw.size(), raw.size() - pendings.size());

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
                                            if ("version_conflict_engine_exception".equals(it.error().type())) {
                                                versionConflictCounter.increment();
                                                slice.get(i).ack.acknowledge();
                                            } else {
                                                log.warn("ES {} 실패 idx={} type={} reason={}",
                                                        operationName(), i, it.error().type(), it.error().reason());
                                            }
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
