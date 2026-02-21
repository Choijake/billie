package com.nextdoor.nextdoor.domain.feed.application.service;

import com.nextdoor.nextdoor.domain.feed.application.port.FeedGeoIndexPort;
import com.nextdoor.nextdoor.domain.feed.application.usecase.MarkPostDeleteUseCase;
import com.nextdoor.nextdoor.domain.feed.infrastructure.exception.InfraException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarkPostDeleteService implements MarkPostDeleteUseCase {

    private final PostMetadataService metadataService;
    private final FeedGeoIndexPort geoIndexPort;

    @Override
    public void markDeleted(Long postId) {
        if (postId == null) return;

        // 삭제 전파는 best-effort로 두고, 실패는 outbox로 재시도하는 구조가 일반적
        try { metadataService.markDeleted(postId); } catch (InfraException ignored) {}
        try { geoIndexPort.removeGeoLocation(postId); } catch (InfraException ignored) {}
    }
}