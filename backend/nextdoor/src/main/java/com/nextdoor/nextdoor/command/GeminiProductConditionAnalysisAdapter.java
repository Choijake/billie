package com.nextdoor.nextdoor.command;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.vertexai.api.Content;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.api.Part;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.PartMaker;
import com.nextdoor.nextdoor.common.Adapter;
import com.nextdoor.nextdoor.domain.aianalysis.controller.dto.response.ProductConditionAnalysisResponseDto;
import com.nextdoor.nextdoor.domain.aianalysis.exception.ExternalApiException;
import com.nextdoor.nextdoor.domain.post.port.ProductConditionAnalysisPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Adapter
@ConditionalOnExpression("'${custom.google.ai.use-real:false}' == 'true'")
public class GeminiProductConditionAnalysisAdapter implements ProductConditionAnalysisPort {

    private final GenerativeModel generativeModel;
    private final Part productConditionAnalyzerPromptPart;
    private final ObjectMapper objectMapper;

    public GeminiProductConditionAnalysisAdapter(
            @Qualifier("geminiFlashHigh")
            GenerativeModel generativeModel,
            @Qualifier("productConditionAnalyzerPromptPart") Part productConditionAnalyzerPromptPart,
            ObjectMapper objectMapper
    ) {
        this.generativeModel = generativeModel;
        this.productConditionAnalyzerPromptPart = productConditionAnalyzerPromptPart;
        this.objectMapper = objectMapper;
    }

    @Override
    public ProductConditionAnalysisResponseDto analyzeProductCondition(MultipartFile productImage) {
        try {
            Content content = createProductConditionAnalysisContent(productImage);
            GenerateContentResponse response = generativeModel.generateContent(content);

            String analysisResult = response.getCandidates(0).getContent().getParts(0).getText();
            String cleanedResult = cleanMarkdownCodeBlocks(analysisResult);

            Map<String, String> resultMap = objectMapper.readValue(cleanedResult, new TypeReference<Map<String, String>>() {});

            String condition = resultMap.get("condition");
            String report = resultMap.get("report");
            String autoFillMessage = resultMap.get("autoFillMessage");

            return ProductConditionAnalysisResponseDto.builder()
                    .condition(condition)
                    .report(report)
                    .suggestAutoFill(true)
                    .autoFillMessage(autoFillMessage)
                    .build();
        } catch (IOException e) {
            throw new ExternalApiException(e);
        }
    }

    private String cleanMarkdownCodeBlocks(String text) {
        Pattern pattern = Pattern.compile("```(?:json)?\\s*\\n?(.*?)\\n?```", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);

        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        return text.trim();
    }

    private Content createProductConditionAnalysisContent(MultipartFile productImage) throws IOException {
        Part imagePart = PartMaker.fromMimeTypeAndData(
                productImage.getContentType(),
                productImage.getBytes()
        );

        return Content.newBuilder()
                .addParts(productConditionAnalyzerPromptPart)
                .addParts(imagePart)
                .setRole("user")
                .build();
    }
}
