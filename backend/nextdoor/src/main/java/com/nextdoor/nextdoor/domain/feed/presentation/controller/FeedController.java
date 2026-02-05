package com.nextdoor.nextdoor.domain.feed.presentation.controller;

import com.nextdoor.nextdoor.domain.feed.application.service.FeedService;
import com.nextdoor.nextdoor.domain.feed.application.service.dto.FeedResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/feed")
@RequiredArgsConstructor
public class FeedController {

    private final FeedService feedService;

    @GetMapping("/home")
    public ResponseEntity<FeedResponse> getHomeFeed(
            @RequestParam Long memberId,
            @RequestParam Double latitude,
            @RequestParam Double longitude,
            @RequestParam(defaultValue = "0") int page
    ) {
        log.info("[Feed Request] memberId={}, lat={}, lon={}, page={}", memberId, latitude, longitude, page);

        var items = feedService.getHomeFeed(memberId, latitude, longitude, page);
        var response = FeedResponse.of(items);

        return ResponseEntity.ok(response);
    }
}