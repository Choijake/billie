package com.nextdoor.nextdoor.domain.post.controller;

import com.nextdoor.nextdoor.domain.post.controller.dto.response.PostListResponse;
import com.nextdoor.nextdoor.domain.post.controller.dto.response.PostLikeResponse;
import com.nextdoor.nextdoor.domain.post.mapper.PostMapper;
import com.nextdoor.nextdoor.domain.post.service.PostLikeService;
import com.nextdoor.nextdoor.domain.post.service.dto.SearchPostCommand;
import com.nextdoor.nextdoor.domain.post.service.dto.SearchPostResult;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 게시글 좋아요 전담 컨트롤러. (SRP)
 */
@RestController
@RequestMapping("/api/v1/posts")
@RequiredArgsConstructor
public class PostLikeController {

    private final PostLikeService postLikeService;
    private final PostMapper postMapper;

    @PostMapping("/{postId}/like")
    public ResponseEntity<PostLikeResponse> likePost(
            @PathVariable Long postId,
            @AuthenticationPrincipal Long userId
    ) {
        boolean success = postLikeService.likePost(postId, userId);
        int likeCount = postLikeService.getPostLikeCount(postId);
        if (success) likeCount++;
        return ResponseEntity.ok(PostLikeResponse.of(postId, true, likeCount));
    }

    @DeleteMapping("/{postId}/like")
    public ResponseEntity<PostLikeResponse> unlikePost(
            @PathVariable Long postId,
            @AuthenticationPrincipal Long userId
    ) {
        postLikeService.unlikePost(postId, userId);
        int likeCount = postLikeService.getPostLikeCount(postId);
        return ResponseEntity.ok(PostLikeResponse.of(postId, false, likeCount));
    }

    @GetMapping("/{postId}/like")
    public ResponseEntity<PostLikeResponse> isPostLikedByUser(
            @PathVariable Long postId,
            @AuthenticationPrincipal Long userId
    ) {
        boolean isLiked = postLikeService.isPostLikedByMember(postId, userId);
        int likeCount = postLikeService.getPostLikeCount(postId);
        return ResponseEntity.ok(PostLikeResponse.of(postId, isLiked, likeCount));
    }

    @GetMapping("/liked")
    public ResponseEntity<Page<PostListResponse>> getLikedPosts(
            @AuthenticationPrincipal Long userId,
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable
    ) {
        SearchPostCommand command = postMapper.toCommand(userId, pageable);
        Page<SearchPostResult> results = postLikeService.getLikedPostsByMember(command);
        return ResponseEntity.ok(results.map(postMapper::toResponse));
    }
}
