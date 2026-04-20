package com.nextdoor.nextdoor.domain.feed.domain.service;

import com.nextdoor.nextdoor.domain.feed.application.service.dto.PostMetadata;
import com.nextdoor.nextdoor.domain.post.domain.Category;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 후보 ID + 메타 + 관심사를 받아 스코어링/정렬/하이브리드 셔플 결과를 만든다.
 *
 * 하이브리드 셔플 전략:
 * - 상위 topUnshuffledCount개: 스코어 순서 유지 (첫 화면 관련성 보장)
 *   - 카테고리 캡(maxPerCategoryInTop)으로 동일 카테고리 독점 방지
 * - 나머지: 사용자×세션별 시드로 셔플 (스크롤 다양성 확보)
 */
@Component
public class FeedGenerator {

    private final PostScorer postScorer;
    private final Shuffler shuffler;
    private final int topUnshuffledCount;
    private final int maxPerCategoryInTop;

    public FeedGenerator(
            PostScorer postScorer,
            Shuffler shuffler,
            @Value("${feed.scoring.top-unshuffled-count:10}") int topUnshuffledCount,
            @Value("${feed.scoring.max-per-category-in-top:3}") int maxPerCategoryInTop) {
        this.postScorer = postScorer;
        this.shuffler = shuffler;
        this.topUnshuffledCount = topUnshuffledCount;
        this.maxPerCategoryInTop = maxPerCategoryInTop;
    }

    public List<Long> generateSessionIds(
            List<Long> candidateIds,
            Map<Long, PostMetadata> metadataMap,
            Map<Category, Long> userInterests,
            int sessionSize,
            long shuffleSeed
    ) {
        if (candidateIds == null || candidateIds.isEmpty()) return Collections.emptyList();

        List<ScoredPost> allScored = candidateIds.stream()
                .map(metadataMap::get)
                .filter(Objects::nonNull)
                .map(md -> new ScoredPost(md.postId(), md.category(),
                        postScorer.calculateScore(md, userInterests)))
                .sorted(Comparator.comparingDouble(ScoredPost::score).reversed())
                .toList();

        List<Long> top = new ArrayList<>();
        List<Long> rest = new ArrayList<>();
        Map<Category, Integer> categoryCounts = new EnumMap<>(Category.class);

        for (ScoredPost sp : allScored) {
            if (top.size() < topUnshuffledCount) {
                int count = categoryCounts.getOrDefault(sp.category(), 0);
                if (sp.category() == null || count < maxPerCategoryInTop) {
                    top.add(sp.id());
                    if (sp.category() != null) {
                        categoryCounts.merge(sp.category(), 1, Integer::sum);
                    }
                    continue;
                }
            }
            if (top.size() + rest.size() < sessionSize) {
                rest.add(sp.id());
            }
        }

        shuffler.shuffle(rest, shuffleSeed);

        List<Long> result = new ArrayList<>(top);
        result.addAll(rest);
        return result;
    }

    private record ScoredPost(Long id, Category category, double score) {}
}
