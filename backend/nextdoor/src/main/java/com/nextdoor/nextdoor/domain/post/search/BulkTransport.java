package com.nextdoor.nextdoor.domain.post.search;

import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface BulkTransport {
    CompletableFuture<BulkResponse> send(List<BulkOperation> ops);
}
