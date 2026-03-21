package com.nextdoor.nextdoor.domain.post.service.usecase;

import com.nextdoor.nextdoor.domain.post.service.dto.SearchPostCommand;
import com.nextdoor.nextdoor.domain.post.service.dto.SearchPostResult;
import org.springframework.data.domain.Page;

public interface PostLikeUseCase {

    boolean likePost(Long postId, Long memberId);

    boolean unlikePost(Long postId, Long memberId);

    boolean isPostLikedByMember(Long postId, Long memberId);

    int getPostLikeCount(Long postId);

    Page<SearchPostResult> getLikedPostsByMember(SearchPostCommand command);
}
