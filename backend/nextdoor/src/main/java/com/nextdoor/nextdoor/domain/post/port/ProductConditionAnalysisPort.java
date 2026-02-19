package com.nextdoor.nextdoor.domain.post.port;

import com.nextdoor.nextdoor.domain.aianalysis.controller.dto.response.ProductConditionAnalysisResponseDto;
import org.springframework.web.multipart.MultipartFile;

public interface ProductConditionAnalysisPort {
    ProductConditionAnalysisResponseDto analyzeProductCondition(MultipartFile productImage);
}