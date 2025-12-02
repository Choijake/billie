package com.nextdoor.nextdoor.config;

import com.google.cloud.vertexai.VertexAI;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatModel;
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration
public class GeminiConfig {

    @Value("${custom.google.ai.gemini.model.flash}")
    private String geminiFlash;

    @Value("${custom.google.ai.gemini.model.pro}")
    private String geminiPro;

    @Value("${custom.google.ai.gemini.model.flashHigh}")
    private String geminiFlashHigh;

    @Value("${custom.damage-analyzer-prompt-location}")
    private String damageAnalyzerPromptLocation;

    @Value("${custom.summarizer-prompt-location}")
    private String summarizerPromptLocation;

    @Value("${custom.pair-damage-comparator-prompt-location}")
    private String pairDamageComparatorPromptLocation;

    @Value("${custom.product-analyzer-prompt-location}")
    private String productAnalyzerPromptLocation;

    @Value("${custom.product-condition-analyzer-prompt-location}")
    private String productConditionAnalyzerPromptLocation;

    @Bean("geminiFlashChatClient")
    public ChatClient geminiFlashChatClient(VertexAI vertexAI) {
        var vertexAiGeminiChatOptions = VertexAiGeminiChatOptions.builder()
                .temperature(0.7)
                .candidateCount(1)
                .model(geminiFlash)
                .build();
        return ChatClient.create(VertexAiGeminiChatModel.builder()
                .vertexAI(vertexAI)
                .defaultOptions(vertexAiGeminiChatOptions)
                .build());
    }

    @Bean("geminiFlashHighChatClient")
    public ChatClient geminiFlashHighChatClient(VertexAI vertexAI) {
        var vertexAiGeminiChatOptions = VertexAiGeminiChatOptions.builder()
                .temperature(0.7)
                .candidateCount(1)
                .model(geminiFlashHigh)
                .build();
        return ChatClient.create(VertexAiGeminiChatModel.builder()
                .vertexAI(vertexAI)
                .defaultOptions(vertexAiGeminiChatOptions)
                .build());
    }

    @Bean("geminiProChatClient")
    public ChatClient geminiProChatClient(VertexAI vertexAI) {
        var vertexAiGeminiChatOptions = VertexAiGeminiChatOptions.builder()
                .temperature(0.7)
                .candidateCount(1)
                .model(geminiPro)
                .build();
        return ChatClient.create(VertexAiGeminiChatModel.builder()
                .vertexAI(vertexAI)
                .defaultOptions(vertexAiGeminiChatOptions)
                .build());
    }

    @Bean(name = "damageAnalyzerPrompt")
    public String damageAnalyzerPrompt(ResourceLoader resourceLoader) {
        return loadPromptText(resourceLoader, damageAnalyzerPromptLocation);
    }

    @Bean(name = "summarizerPrompt")
    public String summarizerPrompt(ResourceLoader resourceLoader) {
        return loadPromptText(resourceLoader, summarizerPromptLocation);
    }

    @Bean(name = "pairDamageComparatorPrompt")
    public String pairDamageComparatorPrompt(ResourceLoader resourceLoader) {
        return loadPromptText(resourceLoader, pairDamageComparatorPromptLocation);
    }

    @Bean(name = "productAnalyzerPrompt")
    public String productAnalyzerPrompt(ResourceLoader resourceLoader) {
        return loadPromptText(resourceLoader, productAnalyzerPromptLocation);
    }

    @Bean(name = "productConditionAnalyzerPrompt")
    public String productConditionAnalyzerPrompt(ResourceLoader resourceLoader) {
        return loadPromptText(resourceLoader, productConditionAnalyzerPromptLocation);
    }

    private String loadPromptText(ResourceLoader resourceLoader, String location) {
        Resource resource = resourceLoader.getResource("classpath:" + location);
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load prompt from " + location, e);
        }
    }
}
