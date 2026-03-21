package com.nextdoor.nextdoor.domain.search.dto;

import java.util.concurrent.CompletableFuture;

public record BatchTask(CompletableFuture<Void> future, long lastId, int count) {
}
