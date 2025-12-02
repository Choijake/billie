package com.nextdoor.nextdoor.domain.post.search.outbox.consumer;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.nextdoor.nextdoor.domain.post.search.outbox.Jsons;
import com.nextdoor.nextdoor.domain.post.search.outbox.event.PostDeleteEvent;
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@RequiredArgsConstructor
@Profile("worker")
@Slf4j
public class DeleteMessageConsumer {

    private static final int MAX_CONCURRENCY   = 2;
    private static final int SLICE_MAX_ACTIONS = 300;
    private static final long SCHEDULE_MS      = 1000;

    private final ElasticsearchAsyncClient es;
    private final Jsons jsons;
    private final MeterRegistry meterRegistry;

    private static class Pending {
        final BulkOperation op;
        final Acknowledgement ack;
        Pending(BulkOperation op, Acknowledgement ack) { this.op = op; this.ack = ack; }
    }

    private final List<Pending> buffer = new CopyOnWriteArrayList<>();

    public void process(String msg, Acknowledgement ack) {
        try {
            PostDeleteEvent e = jsons.toDelete(msg);

            BulkOperation op = BulkOperation.of(b -> b.delete(d -> d
                    .index("posts")
                    .id(String.valueOf(e.getPostId()))
                    .version(e.getVersion())
                    .versionType(VersionType.ExternalGte)
            ));

            buffer.add(new Pending(op, ack));

            if (buffer.size() >= SLICE_MAX_ACTIONS) {
                flush();
            }
        } catch (Exception ex) {
            log.error("삭제 메시지 처리 중 오류: {}", msg, ex);
            // 실패 시 ACK 안 함 → 재전송
        }
    }

    @Scheduled(fixedDelay = SCHEDULE_MS)
    public void periodicFlush() {
        if (!buffer.isEmpty()) flush();
    }

    private synchronized void flush() {
        if (buffer.isEmpty()) return;

        List<Pending> pendings = new ArrayList<>(buffer);
        buffer.clear();

        log.info("삭제 작업 {}건 플러시 시작", pendings.size());

        Timer.Sample t = Timer.start(meterRegistry);
        try {
            List<List<Pending>> slices = sliceByCount(pendings, SLICE_MAX_ACTIONS);

            int idx = 0;
            while (idx < slices.size()) {
                List<CompletableFuture<Void>> window = new ArrayList<>(MAX_CONCURRENCY);

                for (int k = 0; k < MAX_CONCURRENCY && idx < slices.size(); k++, idx++) {
                    List<Pending> slice = slices.get(idx);
                    List<BulkOperation> ops = slice.stream().map(p -> p.op).toList();

                    CompletableFuture<Void> f = es.bulk(b -> b.index("posts").operations(ops))
                            .thenAccept(resp -> {
                                boolean hasErrors = Boolean.TRUE.equals(resp.errors());
                                if (hasErrors) {
                                    List<BulkResponseItem> items = resp.items();
                                    for (int i = 0; i < items.size(); i++) {
                                        var it = items.get(i);
                                        if (it.error() == null) {
                                            slice.get(i).ack.acknowledge();
                                        } else {
                                            log.warn("ES delete 실패 idx={} type={} reason={}",
                                                    i, it.error().type(), it.error().reason());
                                        }
                                    }
                                } else {
                                    slice.forEach(p -> p.ack.acknowledge());
                                }
                                log.debug("삭제 슬라이스 완료 size={}, errors={}",
                                        slice.size(), resp.errors());
                            })
                            .exceptionally(ex -> {
                                log.warn("삭제 슬라이스 실패 size={}", slice.size(), ex);
                                return null;
                            });

                    window.add(f);
                }

                CompletableFuture.allOf(window.toArray(CompletableFuture[]::new)).join();
            }

            log.info("삭제 플러시 완료: {}건", pendings.size());
        } finally {
            t.stop(Timer.builder("indexer.es.bulk.delete")
                    .description("ES delete bulk 병렬 플러시 시간")
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
        try { flush(); } catch (Exception e) { log.warn("삭제 종료 플러시 중 예외", e); }
    }
}