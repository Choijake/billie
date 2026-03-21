package com.nextdoor.nextdoor.domain.post.controller;

import com.nextdoor.nextdoor.domain.aianalysis.controller.dto.response.ProductConditionAnalysisResponseDto;
import com.nextdoor.nextdoor.domain.post.controller.dto.response.AnalyzeProductImageResponse;
import com.nextdoor.nextdoor.domain.post.controller.dto.response.CombinedProductAnalysisResponse;
import com.nextdoor.nextdoor.domain.post.service.ProductAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * AI 상품 이미지 분석 전담 컨트롤러. (SRP)
 * Post CRUD와 독립적이며, 게시글 생성 이전에도 단독 호출 가능.
 */
@RestController
@RequestMapping("/api/v1/posts")
@RequiredArgsConstructor
public class ProductAnalysisController {

    private final ProductAnalysisService productAnalysisService;

    @PostMapping(value = "/analyze-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AnalyzeProductImageResponse> analyzeProductImage(
            @RequestPart("image") MultipartFile image
    ) {
        return ResponseEntity.ok(productAnalysisService.analyzeProductImage(image));
    }

    @PostMapping(value = "/analyze-condition", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProductConditionAnalysisResponseDto> analyzeProductCondition(
            @RequestPart("image") MultipartFile productImage
    ) {
        return ResponseEntity.ok(productAnalysisService.analyzeProductCondition(productImage));
    }

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CombinedProductAnalysisResponse> analyzeProduct(
            @RequestPart("image") MultipartFile productImage
    ) {
        return ResponseEntity.ok(productAnalysisService.analyzeProduct(productImage));
    }
}
