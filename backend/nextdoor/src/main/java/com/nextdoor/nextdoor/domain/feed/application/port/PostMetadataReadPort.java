package com.nextdoor.nextdoor.domain.feed.application.port;

import com.nextdoor.nextdoor.domain.feed.application.service.dto.PostMetadata;

import java.util.List;
import java.util.Map;

public interface PostMetadataReadPort {
    Map<Long, PostMetadata> loadByIds(List<Long> postIds);
}
