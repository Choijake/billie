package com.nextdoor.nextdoor.domain.search.outbox.consumer;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import com.nextdoor.nextdoor.domain.search.config.SearchProperties;
import com.nextdoor.nextdoor.domain.search.document.PostDocument;
import com.nextdoor.nextdoor.domain.search.lock.IndexLockService;
import com.nextdoor.nextdoor.domain.search.lock.PendingEvent;
import com.nextdoor.nextdoor.domain.search.outbox.Jsons;
import com.nextdoor.nextdoor.domain.search.outbox.event.PostDeleteEvent;
import com.nextdoor.nextdoor.domain.search.outbox.event.PostUpsertEvent;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@Profile("worker")
@Slf4j
public class PostIndexConsumer extends AbstractBatchElasticsearchConsumer {

    private final Jsons jsons;

    public PostIndexConsumer(ElasticsearchAsyncClient es, Jsons jsons,
                             MeterRegistry meterRegistry, SearchProperties props,
                             IndexLockService indexLockService) {
        super(es, meterRegistry, props, indexLockService);
        this.jsons = jsons;
    }

    @Override
    protected OperationWithMeta buildOperationWithMeta(String msg) {
        return switch (jsons.readEventType(msg)) {
            case UPSERT -> buildUpsertOperation(msg);
            case DELETE -> buildDeleteOperation(msg);
        };
    }

    private OperationWithMeta buildUpsertOperation(String msg) {
        PostUpsertEvent e = jsons.toUpsert(msg);

        PostDocument doc = PostDocument.builder()
                .id(e.getPostId()).title(e.getTitle()).content(e.getContent())
                .rentalFee(e.getRentalFee() != null ? e.getRentalFee().intValue() : null)
                .deposit(e.getDeposit() != null ? e.getDeposit().intValue() : null)
                .address(e.getAddress())
                .location(e.getLat() != null && e.getLon() != null
                        ? new PostDocument.GeoPoint(e.getLat(), e.getLon()) : null)
                .category(e.getCategory()).likeCount(e.getLikeCount())
                .createdAt(LocalDateTime.parse(e.getCreatedAtIso()))
                .build();

        BulkOperation op = BulkOperation.of(b -> b.index(i -> i
                .index(indexName)
                .id(String.valueOf(e.getPostId()))
                .version(e.getVersion())
                .versionType(VersionType.ExternalGte)
                .document(doc)
        ));
        return new OperationWithMeta(op, e.getPostId(), e.getVersion(), PendingEvent.EventType.UPSERT);
    }

    private OperationWithMeta buildDeleteOperation(String msg) {
        PostDeleteEvent e = jsons.toDelete(msg);

        BulkOperation op = BulkOperation.of(b -> b.delete(d -> d
                .index(indexName)
                .id(String.valueOf(e.getPostId()))
                .version(e.getVersion())
                .versionType(VersionType.ExternalGte)
        ));
        return new OperationWithMeta(op, e.getPostId(), e.getVersion(), PendingEvent.EventType.DELETE);
    }

    @Override
    protected String operationName() {
        return "post-index";
    }

    @Scheduled(fixedDelayString = "${search.bulk.schedule-ms:1000}")
    public void periodicFlush() {
        periodicFlushInternal();
    }
}
