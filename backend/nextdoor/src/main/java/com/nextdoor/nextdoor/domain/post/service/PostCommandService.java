package com.nextdoor.nextdoor.domain.post.service;

import com.nextdoor.nextdoor.domain.post.domain.Category;
import com.nextdoor.nextdoor.domain.post.domain.Post;
import com.nextdoor.nextdoor.domain.post.event.PostLocationEvent;
import com.nextdoor.nextdoor.domain.post.event.PostViewedEvent;
import com.nextdoor.nextdoor.domain.post.exception.NoSuchPostException;
import com.nextdoor.nextdoor.domain.post.mapper.PostMapper;
import com.nextdoor.nextdoor.domain.post.port.PostIndexPort;
import com.nextdoor.nextdoor.domain.post.port.PostQueryPort;
import com.nextdoor.nextdoor.domain.post.repository.PostLikeCountRepository;
import com.nextdoor.nextdoor.domain.post.repository.PostRepository;
import com.nextdoor.nextdoor.domain.post.service.dto.*;
import com.nextdoor.nextdoor.domain.post.service.usecase.PostCommandUseCase;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/**
 * Post CRUD 및 조회에만 집중하는 서비스. (SRP)
 * - 검색 인덱싱은 PostIndexPort에 위임 (DIP)
 * - 이미지 업로드는 PostImageUploadService에 위임 (SRP)
 * - 계측은 PostMetrics에 위임 (SRP)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostCommandService implements PostCommandUseCase {

    private final PostRepository postRepository;
    private final PostQueryPort postQueryPort;
    private final PostLikeCountRepository postLikeCountRepository;
    private final PostMapper postMapper;
    private final PostIndexPort postIndexPort;
    private final PostImageUploadService imageUploadService;
    private final PostMetrics postMetrics;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(readOnly = true)
    public Page<SearchPostResult> searchPostsByUserAddress(SearchPostCommand command) {
        return postQueryPort.searchPostsByMemberAddress(command);
    }

    @Override
    @Transactional(readOnly = true)
    public PostDetailResult getPostDetail(PostDetailCommand command) {
        PostDetailResult result = postQueryPort.getPostDetail(command.getPostId());

        if (command.getUserId() != null && result.getCategory() != null) {
            try {
                Category category = Category.from(result.getCategory());
                eventPublisher.publishEvent(new PostViewedEvent(command.getUserId(), category));
            } catch (Exception e) {
                log.error("게시글 조회 이벤트 발행 실패", e);
            }
        }
        return result;
    }

    @Override
    @Transactional(timeout = 10)
    public CreatePostResult createPost(CreatePostCommand command) {
        Timer.Sample total = postMetrics.startSample();

        Timer.Sample dbSave = postMetrics.startSample();
        Post post = buildNewPost(command);
        Post saved = postRepository.save(post);
        postMetrics.stopDbSave(dbSave);

        publishLocationEventIfPresent(saved);

        postMetrics.recordImageCount(sizeOf(command.getProductImages()));
        postMetrics.recordImageBytes(bytesOf(command.getProductImages()));

        int likeCount = 0;
        Timer.Sample outboxSample = postMetrics.startSample();
        postIndexPort.requestUpsert(saved, likeCount);
        postMetrics.stopOutboxInsert(outboxSample);

        CreatePostResult result = postMapper.toCreateResult(saved, new ArrayList<>());
        postMetrics.stopTotalCreate(total);

        if (!sizeOfList(command.getProductImages()).isEmpty()) {
            imageUploadService.uploadImagesAsync(saved, command.getProductImages());
        }
        return result;
    }

    @Override
    @Transactional(timeout = 10)
    public UpdatePostResult updatePost(UpdatePostCommand command) {
        Post post = postRepository.findByIdAndDeletedFalse(command.getPostId())
                .orElseThrow(() -> new NoSuchPostException("ID가 " + command.getPostId() + "인 게시물이 존재하지 않습니다."));
        if (!post.getAuthorId().equals(command.getAuthorId())) {
            throw new IllegalArgumentException("게시물 작성자만 수정할 수 있습니다.");
        }

        Post updated = buildUpdatedPost(command, post);
        updated = postRepository.save(updated);

        int likeCount = postLikeCountRepository.findById(updated.getId())
                .map(lc -> lc.getLikeCount().intValue()).orElse(0);
        postIndexPort.requestUpsert(updated, likeCount);

        List<String> imageUrls = new ArrayList<>();
        if (command.getProductImages() == null || command.getProductImages().isEmpty()) {
            updated.getProductImages().forEach(img -> imageUrls.add(img.getImageUrl()));
        } else {
            updated = clearAndSaveImages(updated);
            imageUploadService.uploadImagesAsync(updated, command.getProductImages());
        }
        return postMapper.toUpdateResult(updated, imageUrls);
    }

    @Override
    @Transactional(timeout = 10)
    public boolean deletePost(Long postId, Long userId) {
        Post post = postRepository.findByIdAndDeletedFalse(postId)
                .orElseThrow(() -> new NoSuchPostException("ID가 " + postId + "인 게시물이 존재하지 않습니다."));
        if (!post.getAuthorId().equals(userId)) {
            throw new IllegalArgumentException("게시물 작성자만 삭제할 수 있습니다.");
        }

        post.softDelete();
        postRepository.save(post);
        postLikeCountRepository.findById(postId).ifPresent(postLikeCountRepository::delete);
        postIndexPort.requestDelete(postId);
        return true;
    }

    // ──── private helpers ────────────────────────────────────────────────────

    private Post buildNewPost(CreatePostCommand cmd) {
        Double lat = cmd.getPreferredLocation() != null ? cmd.getPreferredLocation().getLatitude() : null;
        Double lon = cmd.getPreferredLocation() != null ? cmd.getPreferredLocation().getLongitude() : null;
        return Post.builder()
                .title(cmd.getTitle()).content(cmd.getContent())
                .rentalFee(cmd.getRentalFee()).deposit(cmd.getDeposit())
                .address(cmd.getAddress()).latitude(lat).longitude(lon)
                .category(cmd.getCategory()).authorId(cmd.getAuthorId())
                .productImages(new ArrayList<>()).deleted(false).deletedAt(null)
                .build();
    }

    private Post buildUpdatedPost(UpdatePostCommand cmd, Post existing) {
        Double lat = cmd.getPreferredLocation() != null ? cmd.getPreferredLocation().getLatitude() : existing.getLatitude();
        Double lon = cmd.getPreferredLocation() != null ? cmd.getPreferredLocation().getLongitude() : existing.getLongitude();
        return Post.builder()
                .id(existing.getId())
                .title(cmd.getTitle() != null ? cmd.getTitle() : existing.getTitle())
                .content(cmd.getContent() != null ? cmd.getContent() : existing.getContent())
                .category(cmd.getCategory() != null ? cmd.getCategory() : existing.getCategory())
                .rentalFee(cmd.getRentalFee() != null ? cmd.getRentalFee() : existing.getRentalFee())
                .deposit(cmd.getDeposit() != null ? cmd.getDeposit() : existing.getDeposit())
                .address(cmd.getAddress() != null ? cmd.getAddress() : existing.getAddress())
                .latitude(lat).longitude(lon)
                .authorId(existing.getAuthorId())
                .productImages(new ArrayList<>(existing.getProductImages()))
                .deleted(existing.isDeleted()).deletedAt(existing.getDeletedAt())
                .build();
    }

    private Post clearAndSaveImages(Post post) {
        Post cleared = Post.builder()
                .id(post.getId()).title(post.getTitle()).content(post.getContent())
                .category(post.getCategory()).rentalFee(post.getRentalFee())
                .deposit(post.getDeposit()).address(post.getAddress())
                .latitude(post.getLatitude()).longitude(post.getLongitude())
                .authorId(post.getAuthorId()).productImages(new ArrayList<>())
                .deleted(post.isDeleted()).deletedAt(post.getDeletedAt())
                .build();
        return postRepository.save(cleared);
    }

    private void publishLocationEventIfPresent(Post post) {
        if (post.getLatitude() != null && post.getLongitude() != null) {
            eventPublisher.publishEvent(PostLocationEvent.builder()
                    .postId(post.getId())
                    .latitude(post.getLatitude())
                    .longitude(post.getLongitude())
                    .build());
        }
    }

    private int sizeOf(List<MultipartFile> images) {
        return images != null ? images.size() : 0;
    }

    private List<MultipartFile> sizeOfList(List<MultipartFile> images) {
        return images != null ? images : List.of();
    }

    private long bytesOf(List<MultipartFile> images) {
        if (images == null) return 0L;
        return images.stream().mapToLong(f -> { try { return f.getSize(); } catch (Exception e) { return 0L; } }).sum();
    }
}
