package com.nextdoor.nextdoor.domain.post.service.usecase;

import com.nextdoor.nextdoor.domain.post.service.dto.*;
import org.springframework.data.domain.Page;

public interface PostCommandUseCase {

    CreatePostResult createPost(CreatePostCommand command);

    UpdatePostResult updatePost(UpdatePostCommand command);

    boolean deletePost(Long postId, Long userId);

    PostDetailResult getPostDetail(PostDetailCommand command);

    Page<SearchPostResult> searchPostsByUserAddress(SearchPostCommand command);
}
