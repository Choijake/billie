package com.nextdoor.nextdoor.domain.feed.application.service;

import com.nextdoor.nextdoor.domain.feed.application.service.dto.FeedItemDto;
import com.nextdoor.nextdoor.domain.feed.application.service.dto.PostMetadata;
import com.nextdoor.nextdoor.domain.feed.domain.service.FeedSessionManager;
import com.nextdoor.nextdoor.domain.feed.infrastructure.persistence.FeedCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedService {

    private final FeedSessionManager sessionManager;
    private final FeedCacheRepository feedRepository;

    public List<FeedItemDto> getHomeFeed(Long memberId, Double lat, Double lon, int page) {
        // 1. 세션 매니저를 통해 보여줄 ID 목록 확보
        List<Long> targetIds = sessionManager.getIdsForPage(memberId, page, lat, lon);

        if (targetIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 상세 정보 조회
        Map<Long, PostMetadata> metadataMap = feedRepository.getPostMetadata(targetIds);

        // 3. 순서대로 DTO 변환
        return targetIds.stream()
                .map(metadataMap::get)
                .filter(Objects::nonNull)
                .map(FeedItemDto::from)
                .collect(Collectors.toList());
    }

    // 위치 인덱싱 메서드
    public void addGeoLocation(Long postId, Double lat, Double lon) {
        feedRepository.addGeoLocation(postId, lat, lon);
    }
}