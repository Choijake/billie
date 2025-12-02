package com.nextdoor.nextdoor.command;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextdoor.nextdoor.common.Adapter;
import com.nextdoor.nextdoor.domain.aianalysis.controller.dto.response.ProductConditionAnalysisResponseDto;
import com.nextdoor.nextdoor.domain.aianalysis.exception.ExternalApiException;
import com.nextdoor.nextdoor.domain.post.controller.dto.response.AnalyzeProductImageResponse;
import com.nextdoor.nextdoor.domain.post.domain.Category;
import com.nextdoor.nextdoor.domain.post.exception.HttpFileReadException;
import com.nextdoor.nextdoor.domain.post.port.ProductAnalysisPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.util.MimeType;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Adapter
public class GeminiProductAnalysisAdapter implements ProductAnalysisPort {

    private final ChatClient chatClient;
    private final String productConditionAnalyzerPrompt;
    private final String productAnalyzerPrompt;

    private final ObjectMapper objectMapper;

    public GeminiProductAnalysisAdapter(
            @Qualifier("geminiFlashHighChatClient")
            ChatClient chatClient,
            @Qualifier("productConditionAnalyzerPrompt")
            String productConditionAnalyzerPrompt,
            @Qualifier("productAnalyzerPrompt")
            String productAnalyzerPrompt,
            ObjectMapper objectMapper
    ) {
        this.chatClient = chatClient;
        this.productConditionAnalyzerPrompt = productConditionAnalyzerPrompt;
        this.productAnalyzerPrompt = productAnalyzerPrompt;
        this.objectMapper = objectMapper;
    }

    @Override
    public ProductConditionAnalysisResponseDto analyzeProductCondition(MultipartFile productImage) {
        Media media = convert(productImage);
        String response = callChatClient(productConditionAnalyzerPrompt, media);
        Map<String, String> resultMap;
        try {
            resultMap = objectMapper.readValue(cleanMarkdownCodeBlocks(response), new TypeReference<>() {});
        } catch (IOException e) {
            throw new ExternalApiException();
        }
        return ProductConditionAnalysisResponseDto.builder()
                .condition(resultMap.get("condition"))
                .report(resultMap.get("report"))
                .suggestAutoFill(true)
                .autoFillMessage(resultMap.get("autoFillMessage"))
                .build();
    }

    @Override
    public AnalyzeProductImageResponse analyzeProductImage(MultipartFile productImage) {
        Media media = convert(productImage);
        String response = callChatClient(productAnalyzerPrompt, media);
        Map<String, String> resultMap;
        try {
            resultMap = objectMapper.readValue(cleanMarkdownCodeBlocks(response), new TypeReference<>() {});
        } catch (IOException e) {
            throw new ExternalApiException();
        }
        return AnalyzeProductImageResponse.builder()
                .title(resultMap.get("title"))
                .content(resultMap.get("content"))
                .category(Category.from(resultMap.get("category")))
                .condition(resultMap.get("condition"))
                .build();
    }

    private Media convert(MultipartFile productImage) {
        MimeType mimeType = MimeType.valueOf(productImage.getContentType());
        byte[] bytes;
        try {
            bytes = productImage.getBytes();
        } catch (IOException e) {
            throw new HttpFileReadException();
        }
        return Media.builder()
                .mimeType(mimeType)
                .data(bytes)
                .build();
    }

    private String callChatClient(String prompt, Media media) {
        return chatClient.prompt()
                .user(u -> u
                        .text(prompt)
                        .media(media))
                .call()
                .content();
    }

    private String cleanMarkdownCodeBlocks(String text) {
        Pattern pattern = Pattern.compile("```(?:json)?\\s*\\n?(.*?)\\n?```", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);

        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        return text.trim();
    }
}
