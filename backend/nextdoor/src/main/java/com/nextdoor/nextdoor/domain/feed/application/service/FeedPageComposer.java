package com.nextdoor.nextdoor.domain.feed.application.service;

import com.nextdoor.nextdoor.domain.feed.application.service.dto.FeedItemDto;
import com.nextdoor.nextdoor.domain.feed.application.service.dto.PostMetadata;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class FeedPageComposer {

    // 삭제 ids 추출
    public Result compose(List<Long> orderedIds, Map<Long, PostMetadata> metadataMap, int limit) {
        List<FeedItemDto> items = new ArrayList<>(limit);
        List<Long> missing = new ArrayList<>();

        for (Long id : orderedIds) {
            PostMetadata md = metadataMap.get(id);
            if (md == null) {
                missing.add(id);
                continue;
            }
            if (items.size() < limit) items.add(FeedItemDto.from(md));
            if (items.size() == limit) break;
        }

        return new Result(items, missing);
    }

    public record Result(List<FeedItemDto> items, List<Long> missingIds) {}
}