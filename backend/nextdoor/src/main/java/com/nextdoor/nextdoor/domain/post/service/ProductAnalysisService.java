package com.nextdoor.nextdoor.domain.post.service;

import com.nextdoor.nextdoor.domain.aianalysis.controller.dto.response.ProductConditionAnalysisResponseDto;
import com.nextdoor.nextdoor.domain.post.controller.dto.response.AnalyzeProductImageResponse;
import com.nextdoor.nextdoor.domain.post.controller.dto.response.CombinedProductAnalysisResponse;
import com.nextdoor.nextdoor.domain.post.port.ProductConditionAnalysisPort;
import com.nextdoor.nextdoor.domain.post.port.ProductImageAnalysisPort;
import com.nextdoor.nextdoor.domain.post.service.usecase.ProductAnalysisUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * AI 이미지 분석에만 집중하는 서비스. (SRP)
 * Post CRUD와 완전히 독립적이며, 포트 인터페이스에만 의존한다. (DIP)
 */
@Service
@RequiredArgsConstructor
public class ProductAnalysisService implements ProductAnalysisUseCase {

    private final ProductImageAnalysisPort productImageAnalysisPort;
    private final ProductConditionAnalysisPort productConditionAnalysisPort;

    @Override
    public AnalyzeProductImageResponse analyzeProductImage(MultipartFile productImage) {
        return productImageAnalysisPort.analyzeProductImage(productImage);
    }

    @Override
    public ProductConditionAnalysisResponseDto analyzeProductCondition(MultipartFile productImage) {
        return productConditionAnalysisPort.analyzeProductCondition(productImage);
    }

    @Override
    public CombinedProductAnalysisResponse analyzeProduct(MultipartFile productImage) {
        AnalyzeProductImageResponse imageResult = analyzeProductImage(productImage);
        ProductConditionAnalysisResponseDto conditionResult = analyzeProductCondition(productImage);
        return CombinedProductAnalysisResponse.from(imageResult, conditionResult);
    }
}
