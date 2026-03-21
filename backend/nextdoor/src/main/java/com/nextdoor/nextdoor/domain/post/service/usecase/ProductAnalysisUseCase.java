package com.nextdoor.nextdoor.domain.post.service.usecase;

import com.nextdoor.nextdoor.domain.aianalysis.controller.dto.response.ProductConditionAnalysisResponseDto;
import com.nextdoor.nextdoor.domain.post.controller.dto.response.AnalyzeProductImageResponse;
import com.nextdoor.nextdoor.domain.post.controller.dto.response.CombinedProductAnalysisResponse;
import org.springframework.web.multipart.MultipartFile;

public interface ProductAnalysisUseCase {

    AnalyzeProductImageResponse analyzeProductImage(MultipartFile productImage);

    ProductConditionAnalysisResponseDto analyzeProductCondition(MultipartFile productImage);

    CombinedProductAnalysisResponse analyzeProduct(MultipartFile productImage);
}
