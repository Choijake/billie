package com.nextdoor.nextdoor.domain.search.outbox.consumer;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import com.nextdoor.nextdoor.domain.search.config.SearchProperties;
import com.nextdoor.nextdoor.domain.search.outbox.Jsons;
import com.nextdoor.nextdoor.domain.search.outbox.event.PostDeleteEvent;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Profile("worker")
@Slf4j
public class DeleteMessageConsumer extends AbstractBatchElasticsearchConsumer {

    private final Jsons jsons;

    public DeleteMessageConsumer(ElasticsearchAsyncClient es, Jsons jsons,
                                  MeterRegistry meterRegistry, SearchProperties props) {
        super(es, meterRegistry, props);
        this.jsons = jsons;
    }

    @Override
    protected BulkOperation buildOperation(String msg) throws Exception {
        PostDeleteEvent e = jsons.toDelete(msg);

        return BulkOperation.of(b -> b.delete(d -> d
                .index(indexName)
                .id(String.valueOf(e.getPostId()))
                .version(e.getVersion())
                .versionType(VersionType.ExternalGte)
        ));
    }

    @Override
    protected String operationName() { return "delete"; }

    @Scheduled(fixedDelayString = "${search.bulk.schedule-ms:1000}")
    public void periodicFlush() {
        periodicFlushInternal();
    }
}
