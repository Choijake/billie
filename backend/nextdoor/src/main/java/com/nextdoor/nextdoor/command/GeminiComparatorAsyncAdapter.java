package com.nextdoor.nextdoor.command;

import com.google.cloud.vertexai.api.Content;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.nextdoor.nextdoor.common.Adapter;
import com.nextdoor.nextdoor.domain.aianalysis.exception.ExternalApiException;
import com.nextdoor.nextdoor.domain.aianalysis.port.GeminiComparatorAsyncPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Async;

import java.util.concurrent.CompletableFuture;

@Adapter
@ConditionalOnExpression("'${custom.google.ai.use-real:false}' == 'true'")
public class GeminiComparatorAsyncAdapter implements GeminiComparatorAsyncPort {

    private final GenerativeModel geminiComparisonModel;

    public GeminiComparatorAsyncAdapter(
            @Qualifier("geminiPro")
            GenerativeModel geminiComparisonModel
    ) {
        this.geminiComparisonModel = geminiComparisonModel;
    }

    @Override
    @Async("asyncExecutor")
    public CompletableFuture<GenerateContentResponse> generateContent(Content content) {
        try {
            return CompletableFuture.completedFuture(geminiComparisonModel.generateContent(content));
        } catch (Exception e) {
            throw new ExternalApiException(e);
        }
    }
}
