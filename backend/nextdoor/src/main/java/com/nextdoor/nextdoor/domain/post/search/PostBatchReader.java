package com.nextdoor.nextdoor.domain.post.search;

import com.nextdoor.nextdoor.domain.post.repository.PostRepository;
import com.nextdoor.nextdoor.domain.post.search.dto.PostBatchResult;
import com.nextdoor.nextdoor.domain.post.search.dto.PostWithLikeCountDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
public class PostBatchReader {

    private static final int BATCH_SIZE = 1000;
    private final PostRepository postRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PostBatchResult findNextBatch(long lastId, LocalDateTime cutOff) {
        Pageable pageRequest = PageRequest.of(0, BATCH_SIZE);
        List<Long> postIds = postRepository.findPostIdsAfterByCutoff(lastId, cutOff, pageRequest);

        if (postIds.isEmpty()) {
            return new PostBatchResult(Collections.emptyList(), lastId);
        }

        List<PostWithLikeCountDto> postDtos = postRepository.findPostsWithLikeCountByIds(postIds);

        long nextLastId = postIds.get(postIds.size() - 1);

        return new PostBatchResult(postDtos, nextLastId);
    }
}