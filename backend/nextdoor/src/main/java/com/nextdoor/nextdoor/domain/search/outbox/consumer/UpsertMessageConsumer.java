package com.nextdoor.nextdoor.domain.search.outbox.consumer;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import com.nextdoor.nextdoor.domain.search.document.PostDocument;
import com.nextdoor.nextdoor.domain.search.config.SearchProperties;
import com.nextdoor.nextdoor.domain.search.outbox.Jsons;
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
public class UpsertMessageConsumer extends AbstractBatchElasticsearchConsumer {

    private final Jsons jsons;

    public UpsertMessageConsumer(ElasticsearchAsyncClient es, Jsons jsons,
                                  MeterRegistry meterRegistry, SearchProperties props) {
        super(es, meterRegistry, props);
        this.jsons = jsons;
    }

    @Override
    protected BulkOperation buildOperation(String msg) throws Exception {
        PostUpsertEvent e = jsons.toUpsert(msg);

        PostDocument doc = PostDocument.builder()
                .id(e.getPostId()).title(e.getTitle()).content(e.getContent())
                .rentalFee(e.getRentalFee()).deposit(e.getDeposit())
                .address(e.getAddress())
                .location(e.getLat() != null && e.getLon() != null
                        ? new PostDocument.GeoPoint(e.getLat(), e.getLon()) : null)
                .category(e.getCategory()).likeCount(e.getLikeCount())
                .createdAt(LocalDateTime.parse(e.getCreatedAtIso()))
                .build();

        return BulkOperation.of(b -> b.index(i -> i
                .index(indexName)
                .id(String.valueOf(e.getPostId()))
                .version(e.getVersion())
                .versionType(VersionType.ExternalGte)
                .document(doc)
        ));
    }

    @Override
    protected String operationName() { return "upsert"; }

    @Scheduled(fixedDelayString = "${search.bulk.schedule-ms:1000}")
    public void periodicFlush() {
        periodicFlushInternal();
    }
}
