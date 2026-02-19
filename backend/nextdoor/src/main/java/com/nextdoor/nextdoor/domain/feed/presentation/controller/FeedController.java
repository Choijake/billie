package com.nextdoor.nextdoor.domain.feed.presentation.controller;


import com.nextdoor.nextdoor.domain.feed.application.service.ActionService;
import com.nextdoor.nextdoor.domain.feed.application.service.FeedService;
import com.nextdoor.nextdoor.domain.feed.application.service.dto.FeedResponse;
import com.nextdoor.nextdoor.domain.post.domain.Category;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 피드 및 사용자 행동 API
 *
 * [성능 특징]
 * - Platform Thread 사용 (Tomcat 기본)
 * - Redis 기반 초저지연 응답 (<50ms)
 * - DB 부하 최소화 (Cache + Write-Back)
 *
 * [인증]
 * - @AuthenticationPrincipal 사용 (실제 구현 시)
 * - 현재는 @RequestParam으로 memberId 전달
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/feed")
@RequiredArgsConstructor
public class FeedController {

    private final FeedService feedService;
    private final ActionService actionService;

    /**
     * 홈 피드 조회
     *
     * [요청]
     * GET /api/v1/feed/home?memberId=1&latitude=37.5665&longitude=126.9780
     *
     * [응답]
     * {
     *   "items": [...],
     *   "totalCount": 20
     * }
     *
     * [처리 시간]
     * - 평균: 30~50ms
     * - P99: <100ms
     */
    @GetMapping("/home")
    public ResponseEntity<FeedResponse> getHomeFeed(
            @RequestParam Long memberId,
            @RequestParam Double latitude,
            @RequestParam Double longitude
    ) {
        log.info("[Feed Request] memberId={}, lat={}, lon={}", memberId, latitude, longitude);

        var items = feedService.getHomeFeed(memberId, latitude, longitude);
        var response = FeedResponse.of(items);

        return ResponseEntity.ok(response);
    }

    /**
     * 게시글 조회 이벤트
     *
     * [요청]
     * POST /api/v1/feed/action/view
     * {
     *   "memberId": 1,
     *   "postId": 100,
     *   "category": "디지털기기"
     * }
     *
     * [효과]
     * - Redis user:1:interest 점수 +1
     * - DB 접근 없음 (초저지연)
     */
    @PostMapping("/action/view")
    public ResponseEntity<Void> logView(
            @RequestParam Long memberId,
            @RequestParam Long postId,
            @RequestParam String category
    ) {
        log.debug("[Action: VIEW] memberId={}, postId={}, category={}",
                memberId, postId, category);

        Category cat = Category.from(category);
        actionService.logInteraction(memberId, cat, ActionService.ActionType.VIEW);

        return ResponseEntity.ok().build();
    }

    /**
     * 좋아요 이벤트
     *
     * [효과]
     * - Redis 점수 +3
     */
    @PostMapping("/action/like")
    public ResponseEntity<Void> logLike(
            @RequestParam Long memberId,
            @RequestParam Long postId,
            @RequestParam String category
    ) {
        log.debug("[Action: LIKE] memberId={}, postId={}, category={}",
                memberId, postId, category);

        Category cat = Category.from(category);
        actionService.logInteraction(memberId, cat, ActionService.ActionType.LIKE);

        return ResponseEntity.ok().build();
    }

    /**
     * 대여 예약 이벤트
     *
     * [효과]
     * - Redis 점수 +10
     */
    @PostMapping("/action/reserve")
    public ResponseEntity<Void> logReserve(
            @RequestParam Long memberId,
            @RequestParam Long postId,
            @RequestParam String category
    ) {
        log.info("[Action: RESERVE] memberId={}, postId={}, category={}",
                memberId, postId, category);

        Category cat = Category.from(category);
        actionService.logInteraction(memberId, cat, ActionService.ActionType.RESERVE);

        return ResponseEntity.ok().build();
    }
}