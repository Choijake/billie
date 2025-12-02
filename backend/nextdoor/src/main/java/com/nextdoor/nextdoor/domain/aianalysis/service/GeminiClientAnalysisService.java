package com.nextdoor.nextdoor.domain.aianalysis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextdoor.nextdoor.domain.aianalysis.controller.dto.request.DamageAnalysisRequestDto;
import com.nextdoor.nextdoor.domain.aianalysis.controller.dto.request.DamageComparisonRequestDto;
import com.nextdoor.nextdoor.domain.aianalysis.controller.dto.response.DamageAnalysisResponseDto;
import com.nextdoor.nextdoor.domain.aianalysis.controller.dto.response.DamageComparisonResponseDto;
import com.nextdoor.nextdoor.domain.aianalysis.event.out.AiCompareAnalysisCompletedEvent;
import com.nextdoor.nextdoor.domain.aianalysis.exception.GeminiResponseProcessingException;
import com.nextdoor.nextdoor.domain.aianalysis.port.AiAnalysisMatcherCommandPort;
import com.nextdoor.nextdoor.domain.aianalysis.port.AiAnalysisRentalQueryPort;
import com.nextdoor.nextdoor.domain.aianalysis.port.AiClientPort;
import com.nextdoor.nextdoor.domain.aianalysis.service.dto.ImageMatcherRequestDto;
import com.nextdoor.nextdoor.domain.aianalysis.service.dto.ImageMatcherResponseDto;
import com.nextdoor.nextdoor.domain.aianalysis.service.dto.RentalDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@Primary
@Transactional
public class GeminiClientAnalysisService implements AiAnalysisService {

    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    private final AiAnalysisRentalQueryPort aiAnalysisRentalQueryPort;
    private final AiAnalysisMatcherCommandPort aiAnalysisMatcherCommandPort;
    private final AiClientPort aiClientPort;

    @Autowired
    public GeminiClientAnalysisService(
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher,
            AiAnalysisRentalQueryPort aiAnalysisRentalQueryPort,
            AiAnalysisMatcherCommandPort aiAnalysisMatcherCommandPort,
            AiClientPort aiClientPort
    ) {
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.aiAnalysisRentalQueryPort = aiAnalysisRentalQueryPort;
        this.aiAnalysisMatcherCommandPort = aiAnalysisMatcherCommandPort;
        this.aiClientPort = aiClientPort;
    }

    @Override
    public DamageAnalysisResponseDto analyzeDamage(Long loginUserId, DamageAnalysisRequestDto damageAnalysisRequestDto) {
        RentalDto rental = aiAnalysisRentalQueryPort.findById(damageAnalysisRequestDto.getRentalId());
        List<RentalDto.AiImageDto> aiImages = rental.getAiImages();
        String response = aiClientPort.analyzeDamage(aiImages);
        return new DamageAnalysisResponseDto(response);
    }

    @Override
    public DamageComparisonResponseDto compareDamage(Long loginUserId, DamageComparisonRequestDto damageComparisonRequestDto) {
        RentalDto rental = aiAnalysisRentalQueryPort.findById(damageComparisonRequestDto.getRentalId());

        // 전 후 이미지 분리
        List<RentalDto.AiImageDto> beforeAiImages = new ArrayList<>();
        List<RentalDto.AiImageDto> afterAiImages = new ArrayList<>();
        rental.getAiImages().forEach(image -> {
            switch (image.getType()) {
                case BEFORE -> beforeAiImages.add(image);
                case AFTER -> afterAiImages.add(image);
            }
        });

        // 이미지 쌍 만들고 비교
        List<RentalDto.AiImageDto[]> aiImagePairs = matchImages(beforeAiImages, afterAiImages);
        List<String> pairResponses = compareImages(aiImagePairs);

        // 프론트로 전할 API 응답 및 이벤트 요소 생성
        List<DamageComparisonResponseDto.MatchingResult> responseMatchingResults = new ArrayList<>();
        List<AiCompareAnalysisCompletedEvent.MatchingResult> eventMatchingResults = new ArrayList<>();
        for (int i = 0; i < pairResponses.size(); i++) {
            try {
                responseMatchingResults.add(new DamageComparisonResponseDto.MatchingResult(
                        aiImagePairs.get(i)[0].getImageUrl(),
                        aiImagePairs.get(i)[1].getImageUrl(),
                        objectMapper.readValue(pairResponses.get(i), DamageComparisonResponseDto.MatchingResult.PairComparisonResult.class)
                ));
            } catch (JsonProcessingException e) {
                throw new GeminiResponseProcessingException("Gemini 응답 변환 도중 예외 발생: " + pairResponses.get(i), e);
            }
            eventMatchingResults.add(new AiCompareAnalysisCompletedEvent.MatchingResult(
                    aiImagePairs.get(i)[0].getAiImageId(),
                    aiImagePairs.get(i)[1].getAiImageId(),
                    pairResponses.get(i)
            ));
        }

        // 손상이 하나라도 있으면 요약하고 아니면 빈 문자열
        List<String> damageDetails = new ArrayList<>();
        responseMatchingResults
                .forEach(matchingResult -> matchingResult.getPairComparisonResult().getDamages()
                        .forEach(damage -> damageDetails.add(damage.getDetails())));
        String summary = responseMatchingResults.stream()
                .anyMatch(matchingResult ->
                        matchingResult.getPairComparisonResult().getResult().equals("DAMAGE_FOUND"))
                ? aiClientPort.summarize(String.join("\n", damageDetails))
                : "";

        // 이벤트 발행 및 API 응답 리턴
        eventPublisher.publishEvent(new AiCompareAnalysisCompletedEvent(
                damageComparisonRequestDto.getRentalId(),
                summary,
                eventMatchingResults));
        return new DamageComparisonResponseDto(
                beforeAiImages.stream().map(RentalDto.AiImageDto::getImageUrl).toList(),
                afterAiImages.stream().map(RentalDto.AiImageDto::getImageUrl).toList(),
                summary,
                responseMatchingResults);
    }

    private List<RentalDto.AiImageDto[]> matchImages(List<RentalDto.AiImageDto> beforeAiImages, List<RentalDto.AiImageDto> afterAiImages) {
        ImageMatcherResponseDto matcherResponse = aiAnalysisMatcherCommandPort.match(
                new ImageMatcherRequestDto(
                        beforeAiImages.stream().map(RentalDto.AiImageDto::getImageUrl).toList(),
                        afterAiImages.stream().map(RentalDto.AiImageDto::getImageUrl).toList()
                ));
        return matcherResponse.getMatches().stream()
                .map(match -> new RentalDto.AiImageDto[] {
                        beforeAiImages.get(match.getBeforeIndex()),
                        afterAiImages.get(match.getAfterIndex())
                })
                .toList();
    }

    private List<String> compareImages(List<RentalDto.AiImageDto[]> aiImagePairs) {
        List<CompletableFuture<String>> futures = new ArrayList<>();
        aiImagePairs.forEach(aiImagePair -> futures.add(aiClientPort.compare(aiImagePair)));
        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }
}
