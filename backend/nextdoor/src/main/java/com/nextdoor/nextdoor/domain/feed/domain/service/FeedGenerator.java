package com.nextdoor.nextdoor.domain.feed.domain.service;

import com.nextdoor.nextdoor.domain.feed.application.service.dto.PostMetadata;
import com.nextdoor.nextdoor.domain.feed.infrastructure.persistence.FeedCacheRepository;
import com.nextdoor.nextdoor.domain.post.domain.Category;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class FeedGenerator {

    private final FeedCacheRepository feedRepository;
    private final PostScorer postScorer;

    private static final int SEARCH_RADIUS_KM = 10;
    private static final int GEO_LIMIT = 200;
    private static final int SESSION_SIZE = 100;

    /**
     * 새로운 피드 목록 생성
     */
    public List<Long> generate(Long memberId, Double lat, Double lon) {
        // 후보군 조회
        List<Long> candidateIds = feedRepository.findNearbyPostIds(lat, lon, SEARCH_RADIUS_KM, GEO_LIMIT);
        if (candidateIds.isEmpty()) return Collections.emptyList();

        // 상세 정보, 스코어 조회
        Map<Long, PostMetadata> metadataMap = feedRepository.getPostMetadata(candidateIds);
        Map<Category, Long> userInterests = feedRepository.getUserInterests(memberId);

        // 점수 계산
        List<ScoredPost> scoredPosts = candidateIds.stream()
                .filter(metadataMap::containsKey)
                .map(id -> new ScoredPost(id, postScorer.calculateScore(metadataMap.get(id), userInterests)))
                .collect(Collectors.toList());

        // 정렬 및 상위 추출
        scoredPosts.sort(Comparator.comparingDouble(ScoredPost::score).reversed());

        List<Long> topIds = scoredPosts.stream()
                .limit(SESSION_SIZE)
                .map(ScoredPost::id)
                .collect(Collectors.toList());

        // 셔플
        Collections.shuffle(topIds);
        return topIds;
    }

    private record ScoredPost(Long id, double score) {}
}