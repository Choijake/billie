package com.nextdoor.nextdoor.domain.post.port;

import com.nextdoor.nextdoor.domain.post.controller.dto.response.AnalyzeProductImageResponse;
import org.springframework.web.multipart.MultipartFile;

public interface ProductImageAnalysisPort {
    AnalyzeProductImageResponse analyzeProductImage(MultipartFile productImage);
}