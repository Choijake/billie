package com.nextdoor.nextdoor.domain.feed.presentation.controller;

import com.nextdoor.nextdoor.domain.feed.application.service.dto.FeedResponse;
import com.nextdoor.nextdoor.domain.feed.application.usecase.GetHomeFeedUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/feed")
@RequiredArgsConstructor
public class FeedController {

    private final GetHomeFeedUseCase getHomeFeedUseCase;

    @GetMapping("/home")
    public ResponseEntity<FeedResponse> getHomeFeed(
            @RequestParam Long memberId,
            @RequestParam Double latitude,
            @RequestParam Double longitude,
            @RequestParam(defaultValue = "0") int page
    ) {
        log.info("[Feed Request] memberId={}, lat={}, lon={}, page={}", memberId, latitude, longitude, page);

        var result = getHomeFeedUseCase.getHomeFeed(memberId, latitude, longitude, page);
        var response = FeedResponse.of(result.items(), result.items().size(), result.hasNext());

        return ResponseEntity.ok(response);
    }
}