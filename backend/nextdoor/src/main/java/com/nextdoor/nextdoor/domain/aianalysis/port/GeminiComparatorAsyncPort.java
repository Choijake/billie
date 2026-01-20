package com.nextdoor.nextdoor.domain.aianalysis.port;

import com.google.cloud.vertexai.api.Content;
import com.google.cloud.vertexai.api.GenerateContentResponse;

import java.util.concurrent.CompletableFuture;

public interface GeminiComparatorAsyncPort {

    CompletableFuture<GenerateContentResponse> generateContent(Content content);
}
