package com.nextdoor.nextdoor.domain.feed.domain.service;

import com.nextdoor.nextdoor.domain.feed.application.service.dto.PostMetadata;
import com.nextdoor.nextdoor.domain.post.domain.Category;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 후보 ID + 메타 + 관심사"를 받아서 스코어링/정렬/상위N/셔플 결과를 만든다
 */
@Component
public class FeedGenerator {

    private final PostScorer postScorer;
    private final Shuffler shuffler;

    public FeedGenerator(PostScorer postScorer, Shuffler shuffler) {
        this.postScorer = postScorer;
        this.shuffler = shuffler;
    }

    public List<Long> generateSessionIds(
            List<Long> candidateIds,
            Map<Long, PostMetadata> metadataMap,
            Map<Category, Long> userInterests,
            int sessionSize
    ) {
        if (candidateIds == null || candidateIds.isEmpty()) return Collections.emptyList();

        List<ScoredPost> scored = candidateIds.stream()
                .map(metadataMap::get)
                .filter(Objects::nonNull)
                .map(md -> new ScoredPost(md.postId(), postScorer.calculateScore(md, userInterests)))
                .sorted(Comparator.comparingDouble(ScoredPost::score).reversed())
                .limit(sessionSize)
                .collect(Collectors.toList());

        List<Long> top = scored.stream().map(ScoredPost::id).collect(Collectors.toList());
        shuffler.shuffle(top);
        return top;
    }

    private record ScoredPost(Long id, double score) {}
}