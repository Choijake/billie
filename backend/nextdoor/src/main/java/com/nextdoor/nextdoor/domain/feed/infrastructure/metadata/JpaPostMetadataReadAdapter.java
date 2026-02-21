package com.nextdoor.nextdoor.domain.feed.infrastructure.metadata;

import com.nextdoor.nextdoor.domain.feed.application.port.PostMetadataReadPort;
import com.nextdoor.nextdoor.domain.feed.application.service.dto.PostMetadata;
import com.nextdoor.nextdoor.domain.post.port.PostFeedMetadata;
import com.nextdoor.nextdoor.domain.post.port.PostFeedMetadataPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Component
@RequiredArgsConstructor
public class JpaPostMetadataReadAdapter implements PostMetadataReadPort {

    private final PostFeedMetadataPort postFeedMetadataPort;

    @Override
    public Map<Long, PostMetadata> loadByIds(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }

        List<PostFeedMetadata> feedMetas = postFeedMetadataPort.loadByIdsForFeed(postIds);

        Map<Long, PostMetadata> result = new HashMap<>(feedMetas.size());
        for (PostFeedMetadata meta : feedMetas) {
            PostMetadata converted = PostMetadata.fromFeedMetadata(meta);
            result.put(converted.postId(), converted);
        }
        return result;
    }
}