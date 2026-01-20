package com.nextdoor.nextdoor.domain.aianalysis.controller;

import com.nextdoor.nextdoor.domain.aianalysis.controller.dto.request.DamageAnalysisRequestDto;
import com.nextdoor.nextdoor.domain.aianalysis.controller.dto.request.DamageComparisonRequestDto;
import com.nextdoor.nextdoor.domain.aianalysis.controller.dto.response.DamageAnalysisResponseDto;
import com.nextdoor.nextdoor.domain.aianalysis.controller.dto.response.DamageComparisonResponseDto;
import com.nextdoor.nextdoor.domain.aianalysis.service.AiAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@ConditionalOnExpression("'${custom.google.ai.use-real:false}' == 'true'")
@RequestMapping("/api/v1/ai")
public class AiAnalysisController {

    private final AiAnalysisService aiAnalysisService;

    @PostMapping("/analyze")
    public ResponseEntity<DamageAnalysisResponseDto> analyzeDamage(
            @AuthenticationPrincipal Long loginUserId,
            @RequestBody DamageAnalysisRequestDto damageAnalysisRequestDto
    ) {
        return ResponseEntity.ok(aiAnalysisService.analyzeDamage(loginUserId, damageAnalysisRequestDto));
    }

    @PostMapping("/compare")
    public ResponseEntity<DamageComparisonResponseDto> compareDamage(
            @AuthenticationPrincipal Long loginUserId,
            @RequestBody DamageComparisonRequestDto damageComparisonRequestDto
    ) {
        return ResponseEntity.ok(aiAnalysisService.compareDamage(loginUserId, damageComparisonRequestDto));
    }
}
