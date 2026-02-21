package com.nextdoor.nextdoor.domain.post.port;

import java.util.List;

public interface PostFeedMetadataPort {

    List<PostFeedMetadata> loadByIdsForFeed(List<Long> postIds);
}