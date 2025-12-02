package com.nextdoor.nextdoor.domain.post.port;

import com.nextdoor.nextdoor.domain.aianalysis.controller.dto.response.ProductConditionAnalysisResponseDto;
import com.nextdoor.nextdoor.domain.post.controller.dto.response.AnalyzeProductImageResponse;
import org.springframework.web.multipart.MultipartFile;

public interface ProductAnalysisPort {

    ProductConditionAnalysisResponseDto analyzeProductCondition(MultipartFile productImage);

    AnalyzeProductImageResponse analyzeProductImage(MultipartFile productImage);
}
