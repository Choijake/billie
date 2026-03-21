package com.nextdoor.nextdoor.domain.post.controller;

import com.nextdoor.nextdoor.domain.post.controller.dto.response.CreatePostResponse;
import com.nextdoor.nextdoor.domain.post.controller.dto.response.PostDetailResponse;
import com.nextdoor.nextdoor.domain.post.controller.dto.response.PostListResponse;
import com.nextdoor.nextdoor.domain.post.controller.dto.response.UpdatePostResponse;
import com.nextdoor.nextdoor.domain.post.controller.dto.request.CreatePostRequest;
import com.nextdoor.nextdoor.domain.post.controller.dto.request.UpdatePostRequest;
import com.nextdoor.nextdoor.domain.post.mapper.PostMapper;
import com.nextdoor.nextdoor.domain.post.service.PostCommandService;
import com.nextdoor.nextdoor.domain.post.service.PostLikeService;
import com.nextdoor.nextdoor.domain.post.service.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Post CRUD 전담 컨트롤러. (SRP)
 * 좋아요 → PostLikeController, AI 분석 → ProductAnalysisController로 분리.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostCommandService postCommandService;
    private final PostLikeService postLikeService;
    private final PostMapper postMapper;

    @GetMapping
    public ResponseEntity<Page<PostListResponse>> getPostsByUserAddress(
            @AuthenticationPrincipal Long userId,
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable
    ) {
        SearchPostCommand command = postMapper.toCommand(userId, pageable);
        Page<SearchPostResult> results = postCommandService.searchPostsByUserAddress(command);
        return ResponseEntity.ok(results.map(postMapper::toResponse));
    }

    @GetMapping("/{postId}")
    public ResponseEntity<PostDetailResponse> getPostDetail(
            @PathVariable Long postId,
            @AuthenticationPrincipal Long userId
    ) {
        PostDetailCommand command = postMapper.toDetailCommand(postId, userId);
        PostDetailResult result = postCommandService.getPostDetail(command);
        boolean isLiked = postLikeService.isPostLikedByMember(postId, userId);
        return ResponseEntity.ok(postMapper.toDetailResponse(result, isLiked));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CreatePostResponse> createPost(
            @RequestPart("post") @Valid CreatePostRequest request,
            @RequestPart(value = "images", required = false) List<MultipartFile> images,
            @AuthenticationPrincipal Long authorId
    ) {
        CreatePostCommand command = postMapper.toCreateCommand(request, images, authorId);
        CreatePostResult result = postCommandService.createPost(command);
        return ResponseEntity.ok(postMapper.toCreateResponse(result));
    }

    @PutMapping(value = "/{postId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UpdatePostResponse> updatePost(
            @PathVariable Long postId,
            @RequestPart("post") @Valid UpdatePostRequest request,
            @RequestPart(value = "images", required = false) List<MultipartFile> images,
            @AuthenticationPrincipal Long authorId
    ) {
        UpdatePostCommand command = postMapper.toUpdateCommand(request, images, postId, authorId);
        UpdatePostResult result = postCommandService.updatePost(command);
        return ResponseEntity.ok(postMapper.toUpdateResponse(result));
    }

    @DeleteMapping("/{postId}")
    public ResponseEntity<Void> deletePost(
            @PathVariable Long postId,
            @AuthenticationPrincipal Long userId
    ) {
        boolean success = postCommandService.deletePost(postId, userId);
        return success ? ResponseEntity.noContent().build() : ResponseEntity.badRequest().build();
    }
}
