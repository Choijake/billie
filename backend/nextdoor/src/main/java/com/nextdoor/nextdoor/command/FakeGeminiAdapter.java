package com.nextdoor.nextdoor.command;

import com.google.cloud.vertexai.api.Content;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.nextdoor.nextdoor.common.Adapter;
import com.nextdoor.nextdoor.domain.aianalysis.controller.dto.response.ProductConditionAnalysisResponseDto;
import com.nextdoor.nextdoor.domain.aianalysis.port.GeminiComparatorAsyncPort;
import com.nextdoor.nextdoor.domain.post.controller.dto.response.AnalyzeProductImageResponse;
import com.nextdoor.nextdoor.domain.post.domain.Category;
import com.nextdoor.nextdoor.domain.post.port.ProductConditionAnalysisPort;
import com.nextdoor.nextdoor.domain.post.port.ProductImageAnalysisPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.multipart.MultipartFile;

import java.util.concurrent.CompletableFuture;

@Adapter
// [수정] 복잡한 MissingBean 대신, 설정값에 따라 반대로 동작하도록 변경
// use-real이 false(기본값)이거나 설정이 없으면 이 가짜 어댑터가 뜹니다.
@ConditionalOnExpression("'${custom.google.ai.use-real:false}' == 'false'")
public class FakeGeminiAdapter implements
        ProductConditionAnalysisPort,
        ProductImageAnalysisPort,
        GeminiComparatorAsyncPort {

    // 1. 물품 상태 분석 가짜 응답
    @Override
    public ProductConditionAnalysisResponseDto analyzeProductCondition(MultipartFile productImage) {
        return ProductConditionAnalysisResponseDto.builder()
                .condition("최상")
                .report("[Fake] 테스트용 상태 분석 결과입니다. 상태는 아주 좋습니다.")
                .suggestAutoFill(true)
                .autoFillMessage("자동 채움 메시지 예시")
                .build();
    }

    // 2. 물품 이미지 분석 가짜 응답
    @Override
    public AnalyzeProductImageResponse analyzeProductImage(MultipartFile productImage) {
        return AnalyzeProductImageResponse.builder()
                .title("[Fake] 테스트용 상품 제목")
                .content("[Fake] 테스트용 상품 내용입니다.")
                .category(Category.DIGITAL_DEVICE)
                .condition("상")
                .build();
    }

    // 3. 비교 분석 가짜 응답 (비동기)
    @Override
    public CompletableFuture<GenerateContentResponse> generateContent(Content content) {
        return CompletableFuture.completedFuture(
                GenerateContentResponse.newBuilder().build()
        );
    }
}