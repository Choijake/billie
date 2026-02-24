package com.nextdoor.nextdoor.domain.feed.application.service;

import com.nextdoor.nextdoor.domain.feed.application.port.FeedGeoIndexPort;
import com.nextdoor.nextdoor.domain.feed.application.usecase.MarkPostDeleteUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MarkPostDeleteService implements MarkPostDeleteUseCase {

    private final PostMetadataService metadataService;
    private final FeedGeoIndexPort geoIndexPort;
    private final BestEffortExecutor bestEffortExecutor;

    @Override
    public void markDeleted(Long postId) {
        if (postId == null) return;

        // 삭제 전파는 best-effort로 두고, 실패는 outbox로 재시도하는 구조가 일반적
        bestEffortExecutor.run("feed.markDeleted.metadata", () -> metadataService.markDeleted(postId));
        bestEffortExecutor.run("feed.markDeleted.geo", () -> geoIndexPort.removeGeoLocation(postId));
    }
}
