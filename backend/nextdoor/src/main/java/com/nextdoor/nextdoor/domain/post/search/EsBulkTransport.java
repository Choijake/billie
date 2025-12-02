package com.nextdoor.nextdoor.domain.post.search;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class EsBulkTransport implements BulkTransport {
    private final ElasticsearchAsyncClient es;

    public EsBulkTransport(ElasticsearchAsyncClient es) {
        this.es = es;
    }

    @Override
    public CompletableFuture<BulkResponse> send(List<BulkOperation> ops) {
        BulkRequest req = BulkRequest.of(b -> b.operations(ops));
        return es.bulk(req);
    }
}
