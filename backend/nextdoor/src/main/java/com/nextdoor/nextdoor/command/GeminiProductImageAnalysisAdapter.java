package com.nextdoor.nextdoor.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.vertexai.api.Content;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.api.Part;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.PartMaker;
import com.nextdoor.nextdoor.common.Adapter;
import com.nextdoor.nextdoor.domain.aianalysis.exception.ExternalApiException;
import com.nextdoor.nextdoor.domain.post.controller.dto.response.AnalyzeProductImageResponse;
import com.nextdoor.nextdoor.domain.post.domain.Category;
import com.nextdoor.nextdoor.domain.post.port.ProductImageAnalysisPort;
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
public class GeminiProductImageAnalysisAdapter implements ProductImageAnalysisPort {

    private final GenerativeModel generativeModel;

    private final Part productAnalyzerPromptPart;

    private final ObjectMapper objectMapper;

    public GeminiProductImageAnalysisAdapter(
            @Qualifier("geminiFlashHigh")
            GenerativeModel generativeModel,
            @Qualifier("productAnalyzerPromptPart") Part productAnalyzerPromptPart,
            ObjectMapper objectMapper
    ) {
        this.generativeModel = generativeModel;
        this.productAnalyzerPromptPart = productAnalyzerPromptPart;
        this.objectMapper = objectMapper;
    }

    @Override
    public AnalyzeProductImageResponse analyzeProductImage(MultipartFile productImage) {
        try {
            GenerateContentResponse response = generativeModel.generateContent(createAnalysisContent(productImage));
            String analysisResult = response.getCandidates(0).getContent().getParts(0).getText();

            // 마크다운 코드 블록 제거
            String cleanedResult = cleanMarkdownCodeBlocks(analysisResult);

            Map<String, Object> resultMap = objectMapper.readValue(cleanedResult, Map.class);

            return AnalyzeProductImageResponse.builder()
                    .title((String) resultMap.get("title"))
                    .content((String) resultMap.get("content"))
                    .category(Category.from((String) resultMap.get("category")))
                    .condition((String) resultMap.get("condition"))
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

    private Content createAnalysisContent(MultipartFile productImage) throws IOException {
        Part imagePart = PartMaker.fromMimeTypeAndData(
                productImage.getContentType(),
                productImage.getBytes()
        );

        return Content.newBuilder()
                .addParts(imagePart)
                .addParts(productAnalyzerPromptPart)
                .setRole("user")
                .build();
    }
}
