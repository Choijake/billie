package com.nextdoor.nextdoor.domain.feed.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextdoor.nextdoor.domain.feed.application.service.dto.FeedItemDto;
import com.nextdoor.nextdoor.domain.feed.application.service.dto.PostMetadata;
import com.nextdoor.nextdoor.domain.feed.domain.UserInterestScore;
import com.nextdoor.nextdoor.domain.feed.infrastructure.persistence.UserInterestScoreRepository;
import com.nextdoor.nextdoor.domain.post.domain.Category;
import com.nextdoor.nextdoor.domain.post.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 위치 기반 개인화 피드 서비스
 *
 * [수정 사항]
 * - Redis 직렬화 문제 해결을 위해 StringRedisTemplate + ObjectMapper 사용
 * - Geo 조회 시 순수 문자열 ID 처리
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedService {

    private final StringRedisTemplate redisTemplate; // [핵심 변경] 모든 Redis 작업을 이걸로 통일
    private final UserInterestScoreRepository scoreRepository;
    private final PostRepository postRepository;
    private final ObjectMapper objectMapper; // [추가] JSON 수동 변환용

    private static final String GEO_KEY = "feed:geo";
    private static final double SEARCH_RADIUS_KM = 10.0;
    private static final int CANDIDATE_SIZE = 200;
    private static final int TOP_N = 50;
    private static final int FINAL_SIZE = 20;

    /**
     * 홈 피드 조회
     */
    public List<FeedItemDto> getHomeFeed(Long memberId, Double latitude, Double longitude) {
        long startTime = System.currentTimeMillis();

        try {
            // 1. Geo Radius 조회 (StringRedisTemplate 사용)
            List<String> candidatePostIds = findNearbyPosts(latitude, longitude);

            // 데이터가 없으면 빠른 리턴
            if (candidatePostIds.isEmpty()) {
                log.debug("[Geo Search] 결과 없음: lat={}, lon={}", latitude, longitude);
                return Collections.emptyList();
            }
            log.debug("[Geo Search] 후보군: {}개", candidatePostIds.size());

            // 2. 메타데이터 조회 (JSON 문자열 -> 객체 변환)
            Map<Long, PostMetadata> metadataMap = fetchMetadata(candidatePostIds);
            log.debug("[Metadata Fetch] 조회 성공: {}개", metadataMap.size());

            // 3. 사용자 관심사 로드
            Map<Category, Long> userInterests = loadUserInterests(memberId);

            // 4. 점수 계산
            List<ScoredPost> scoredPosts = calculateScores(metadataMap, userInterests);

            // 5. 랭킹 & 셔플
            List<FeedItemDto> result = rankAndShuffle(scoredPosts);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[Feed Generated] memberId={}, 처리 시간={}ms, 결과={}개",
                    memberId, elapsed, result.size());

            return result;

        } catch (Exception e) {
            log.error("[Feed Failed] memberId={}, error={}", memberId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Redis Geo Radius 조회
     * [Fix] StringRedisTemplate을 사용하여 직렬화 에러 방지
     */
    private List<String> findNearbyPosts(Double latitude, Double longitude) {
        Point center = new Point(longitude, latitude);
        Distance radius = new Distance(SEARCH_RADIUS_KM, Metrics.KILOMETERS);

        // StringRedisTemplate을 쓰면 <String> 타입으로 리턴됨
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redisTemplate.opsForGeo()
                .radius(
                        GEO_KEY,
                        new Circle(center, radius),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .sortAscending()
                                .limit(CANDIDATE_SIZE)
                );

        if (results == null) {
            return Collections.emptyList();
        }

        return results.getContent().stream()
                .map(result -> result.getContent().getName()) // "50" 같은 문자열 추출
                .collect(Collectors.toList());
    }

    /**
     * 메타데이터 조회
     * [Fix] MGET으로 JSON 문자열을 가져온 뒤 ObjectMapper로 변환
     */
    private Map<Long, PostMetadata> fetchMetadata(List<String> postIds) {
        Map<Long, PostMetadata> result = new HashMap<>();

        // Redis Key 생성
        List<String> cacheKeys = postIds.stream()
                .map(id -> "post:" + id + ":info")
                .collect(Collectors.toList());

        // MGET (List<String> 반환됨)
        List<String> cachedJsonList = redisTemplate.opsForValue().multiGet(cacheKeys);

        if (cachedJsonList == null) {
            cachedJsonList = Collections.emptyList();
        }

        List<Long> missedIds = new ArrayList<>();

        for (int i = 0; i < postIds.size(); i++) {
            Long postId = Long.parseLong(postIds.get(i));

            // 리스트 범위 체크 안전장치 (혹시 모를 인덱스 에러 방지)
            String json = (i < cachedJsonList.size()) ? cachedJsonList.get(i) : null;

            if (StringUtils.hasText(json)) {
                try {
                    // JSON String -> Object 수동 변환
                    PostMetadata metadata = objectMapper.readValue(json, PostMetadata.class);
                    result.put(postId, metadata);
                } catch (JsonProcessingException e) {
                    log.warn("메타데이터 파싱 실패: postId={}, json={}", postId, json);
                    missedIds.add(postId); // 파싱 실패 시 DB에서 다시 조회
                }
            } else {
                missedIds.add(postId);
            }
        }

        // Cache Miss 처리
        if (!missedIds.isEmpty()) {
            loadFromDBAndCache(missedIds, result);
        }

        return result;
    }

    /**
     * DB 조회 및 Redis 캐싱
     * [Fix] 객체를 JSON 문자열로 변환하여 저장
     */
    private void loadFromDBAndCache(List<Long> postIds, Map<Long, PostMetadata> result) {
        var posts = postRepository.findAllById(postIds);

        // Pipeline을 사용하여 네트워크 비용 최적화 가능하지만, 여기선 간단히 set으로 구현
        for (var post : posts) {
            PostMetadata metadata = PostMetadata.builder()
                    .postId(post.getId())
                    .title(post.getTitle())
                    .category(post.getCategory())
                    .rentalFee(post.getRentalFee())
                    .deposit(post.getDeposit())
                    .createdAt(post.getCreatedAt())
                    .imageUrl(post.getProductImages().isEmpty() ? null :
                            post.getProductImages().get(0).getImageUrl())
                    .build();

            result.put(post.getId(), metadata);

            try {
                // Object -> JSON String 변환
                String json = objectMapper.writeValueAsString(metadata);
                String cacheKey = "post:" + post.getId() + ":info";

                // 1시간 TTL 설정
                redisTemplate.opsForValue().set(cacheKey, json, java.time.Duration.ofHours(1));
            } catch (JsonProcessingException e) {
                log.error("메타데이터 캐싱 실패: postId={}", post.getId(), e);
            }
        }
    }

    /**
     * 사용자 관심사 로드
     * [Fix] Redis Hash 값을 String으로 읽어서 파싱
     */
    private Map<Category, Long> loadUserInterests(Long memberId) {
        Map<Category, Long> interests = new HashMap<>();
        String key = "user:" + memberId + ":interest";

        // opsForHash().entries()는 Map<Object, Object>를 반환하지만,
        // StringRedisTemplate을 쓰면 실제로는 Map<String, String>임
        Map<Object, Object> redisScores = redisTemplate.opsForHash().entries(key);

        if (!redisScores.isEmpty()) {
            redisScores.forEach((k, v) -> {
                try {
                    String categoryStr = (String) k;
                    String scoreStr = (String) v;

                    Category category = Category.valueOf(categoryStr);
                    Long score = Long.parseLong(scoreStr);

                    interests.put(category, score);
                } catch (Exception e) {
                    log.warn("관심사 파싱 무시: key={}, val={}", k, v);
                }
            });
        } else {
            // DB Fallback
            List<UserInterestScore> scores = scoreRepository.findByMemberId(memberId);
            for (UserInterestScore score : scores) {
                interests.put(score.getCategory(), score.getScore());
            }
        }

        return interests;
    }

    /**
     * 개인화 점수 계산 (기존 로직 유지)
     */
    private List<ScoredPost> calculateScores(
            Map<Long, PostMetadata> metadataMap,
            Map<Category, Long> userInterests
    ) {
        List<ScoredPost> scoredPosts = new ArrayList<>();
        Random random = new Random();

        long maxInterest = userInterests.values().stream()
                .max(Long::compare)
                .orElse(1L);

        for (PostMetadata metadata : metadataMap.values()) {
            double recencyScore = calculateRecencyScore(metadata.createdAt());
            long rawInterest = userInterests.getOrDefault(metadata.category(), 0L);
            double interestScore = (rawInterest * 100.0) / maxInterest;
            double randomNoise = random.nextDouble() * 5;

            double finalScore = (recencyScore * 0.3) + (interestScore * 0.5) + (randomNoise * 0.2);

            scoredPosts.add(new ScoredPost(metadata, finalScore));
        }
        return scoredPosts;
    }

    private double calculateRecencyScore(LocalDateTime createdAt) {
        if (createdAt == null) return 10.0; // 방어 코드
        long daysAgo = java.time.Duration.between(createdAt, LocalDateTime.now()).toDays();
        if (daysAgo <= 1) return 100.0;
        if (daysAgo <= 7) return 70.0;
        if (daysAgo <= 30) return 40.0;
        return 10.0;
    }

    /**
     * 랭킹 및 셔플 (기존 로직 유지)
     */
    private List<FeedItemDto> rankAndShuffle(List<ScoredPost> scoredPosts) {
        scoredPosts.sort(Comparator.comparingDouble(ScoredPost::score).reversed());

        List<ScoredPost> topPosts = scoredPosts.stream()
                .limit(TOP_N)
                .collect(Collectors.toList());

        Collections.shuffle(topPosts);

        return topPosts.stream()
                .limit(FINAL_SIZE)
                .map(sp -> FeedItemDto.from(sp.metadata()))
                .collect(Collectors.toList());
    }

    /**
     * 게시글 위치 인덱싱
     * [Fix] ID를 String으로 변환하여 저장
     */
    public void addGeoLocation(Long postId, Double latitude, Double longitude) {
        try {
            Point location = new Point(longitude, latitude);
            // StringRedisTemplate이므로 ID를 String으로 변환
            redisTemplate.opsForGeo().add(GEO_KEY, location, String.valueOf(postId));
            log.debug("[Geo Indexed] postId={}, lat={}, lon={}", postId, latitude, longitude);
        } catch (Exception e) {
            log.error("[Geo Index Failed] postId={}", postId, e);
        }
    }

    /**
     * 게시글 위치 삭제
     */
    public void removeGeoLocation(Long postId) {
        try {
            redisTemplate.opsForGeo().remove(GEO_KEY, String.valueOf(postId));
            log.debug("[Geo Removed] postId={}", postId);
        } catch (Exception e) {
            log.error("[Geo Remove Failed] postId={}", postId, e);
        }
    }

    private record ScoredPost(PostMetadata metadata, double score) {}
}