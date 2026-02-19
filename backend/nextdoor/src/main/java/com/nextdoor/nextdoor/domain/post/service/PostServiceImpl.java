package com.nextdoor.nextdoor.domain.post.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextdoor.nextdoor.domain.aianalysis.controller.dto.response.ProductConditionAnalysisResponseDto;
import com.nextdoor.nextdoor.domain.feed.application.service.FeedService;
import com.nextdoor.nextdoor.domain.post.controller.dto.response.AnalyzeProductImageResponse;
import com.nextdoor.nextdoor.domain.post.controller.dto.response.CombinedProductAnalysisResponse;
import com.nextdoor.nextdoor.domain.post.domain.Post;
import com.nextdoor.nextdoor.domain.post.domain.PostLikeCount;
import com.nextdoor.nextdoor.domain.post.exception.NoSuchPostException;
import com.nextdoor.nextdoor.domain.post.mapper.PostMapper;
import com.nextdoor.nextdoor.domain.post.port.*;
import com.nextdoor.nextdoor.domain.post.repository.PostLikeCountRepository;
import com.nextdoor.nextdoor.domain.post.repository.PostLikeRepository;
import com.nextdoor.nextdoor.domain.post.repository.PostRepository;
import com.nextdoor.nextdoor.domain.post.search.outbox.OutboxEvent;
import com.nextdoor.nextdoor.domain.post.search.outbox.OutboxEventRepository;
import com.nextdoor.nextdoor.domain.post.event.PostLocationEvent;
import com.nextdoor.nextdoor.domain.post.search.outbox.event.PostDeleteEvent;
import com.nextdoor.nextdoor.domain.post.search.outbox.event.PostUpsertEvent;
import com.nextdoor.nextdoor.domain.post.service.dto.*;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {

    private final PostQueryPort postQueryPort;
    private final PostRepository postRepository;
    private final PostLikeRepository postLikeRepository;
    private final PostLikeCountRepository postLikeCountRepository;
    private final S3ImageUploadPort s3ImageUploadPort;
    private final PostMapper postMapper;
    private final ProductImageAnalysisPort productImageAnalysisPort;
    private final ProductConditionAnalysisPort productConditionAnalysisPort;
    private final OutboxEventRepository outboxRepo;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final ApplicationEventPublisher eventPublisher;

    private Timer outboxInsertTimer;
    private Timer dbSaveTimer;
    private Timer outboxSerializeTimer;
    private Timer outboxSaveTimer;
    private Timer imageUploadOneTimer;
    private Timer imageUploadAllTimer;
    private Timer totalTimer;
    private DistributionSummary imageCountSummary;
    private DistributionSummary imageBytesSummary;

    @PostConstruct
    public void initMetrics() {
        this.outboxInsertTimer = Timer.builder("outbox.insert.latency")
                .description("DB TX 종료~Outbox 저장 구간")
                .tag("aggregate", "post")
                .publishPercentileHistogram()
                .register(meterRegistry);

        this.dbSaveTimer = Timer.builder("post.create.phase")
                .tag("phase", "db.save")
                .publishPercentileHistogram()
                .register(meterRegistry);

        this.outboxSerializeTimer = Timer.builder("post.create.phase")
                .tag("phase", "outbox.serialize")
                .publishPercentileHistogram()
                .register(meterRegistry);

        this.outboxSaveTimer = Timer.builder("post.create.phase")
                .tag("phase", "outbox.save")
                .publishPercentileHistogram()
                .register(meterRegistry);

        this.imageUploadOneTimer = Timer.builder("post.create.phase")
                .tag("phase", "image.upload.one")
                .publishPercentileHistogram()
                .register(meterRegistry);

        this.imageUploadAllTimer = Timer.builder("post.create.phase")
                .tag("phase", "image.upload.all")
                .publishPercentileHistogram()
                .register(meterRegistry);

        this.totalTimer = Timer.builder("post.create.total")
                .publishPercentileHistogram()
                .register(meterRegistry);

        this.imageCountSummary = DistributionSummary.builder("post.images.count")
                .register(meterRegistry);

        this.imageBytesSummary = DistributionSummary.builder("post.images.bytes")
                .register(meterRegistry);
    }

    private void recordOutboxInsert(Runnable action) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            action.run();
        } finally {
            sample.stop(outboxInsertTimer);
        }
    }


    @Override
    @Transactional(readOnly = true)
    public Page<SearchPostResult> searchPostsByUserAddress(SearchPostCommand command) {
        return postQueryPort.searchPostsByMemberAddress(command);
    }

    @Override
    @Transactional(readOnly = true)
    public PostDetailResult getPostDetail(PostDetailCommand command) {
        return postQueryPort.getPostDetail(command.getPostId());
    }

    @Override
    @Transactional(timeout = 10)
    public CreatePostResult createPost(CreatePostCommand command) {
        final Timer.Sample totalSample = Timer.start(meterRegistry);

        Double latitude = null;
        Double longitude = null;
        if (command.getPreferredLocation() != null) {
            latitude = command.getPreferredLocation().getLatitude();
            longitude = command.getPreferredLocation().getLongitude();
        }

        Timer.Sample dbSave = Timer.start(meterRegistry);
        Post post = Post.builder()
                .title(command.getTitle())
                .content(command.getContent())
                .rentalFee(command.getRentalFee())
                .deposit(command.getDeposit())
                .address(command.getAddress())
                .latitude(latitude)
                .longitude(longitude)
                .category(command.getCategory())
                .authorId(command.getAuthorId())
                .productImages(new ArrayList<>())
                .build();
        Post savedPost = postRepository.save(post);
        dbSave.stop(dbSaveTimer);

        if (savedPost.getLatitude() != null && savedPost.getLongitude() != null) {
            PostLocationEvent locationEvent = PostLocationEvent.builder()
                    .postId(savedPost.getId())
                    .latitude(savedPost.getLatitude())
                    .longitude(savedPost.getLongitude())
                    .build();

            eventPublisher.publishEvent(locationEvent);
            log.debug("위치 정보 이벤트 발행: postId={}, lat={}, lon={}", 
                    savedPost.getId(), savedPost.getLatitude(), savedPost.getLongitude());
        }

        Timer.Sample outboxSerialize = Timer.start(meterRegistry);
        long version = savedPost.getUpdatedAt()
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

        PostUpsertEvent evt = PostUpsertEvent.builder()
                .postId(savedPost.getId()).version(version)
                .title(savedPost.getTitle()).content(savedPost.getContent())
                .rentalFee(savedPost.getRentalFee()).deposit(savedPost.getDeposit())
                .address(savedPost.getAddress())
                .lat(savedPost.getLatitude()).lon(savedPost.getLongitude())
                .category(savedPost.getCategory() != null ? savedPost.getCategory().name() : null)
                .likeCount(0)
                .createdAtIso(savedPost.getCreatedAt().toString())
                .build();

        OutboxEvent ob = new OutboxEvent();
        ob.setAggregateType("POST");
        ob.setAggregateId(savedPost.getId());
        ob.setEventType("UPSERT");
        try {
            ob.setPayload(objectMapper.writeValueAsString(evt));
        } catch (Exception e) {
            outboxSerialize.stop(outboxSerializeTimer);
            throw new RuntimeException("이벤트 직렬화 실패", e);
        }
        outboxSerialize.stop(outboxSerializeTimer);

        ob.setVersion(version);
        ob.setCreatedAt(savedPost.getUpdatedAt());
        ob.setPublished(false);

        Timer.Sample outboxSave = Timer.start(meterRegistry);
        recordOutboxInsert(() -> outboxRepo.save(ob));
        outboxSave.stop(outboxSaveTimer);

        List<MultipartFile> images = command.getProductImages() != null ? command.getProductImages() : List.of();

        imageCountSummary.record(images.size());

        long totalBytes = 0L;
        for (MultipartFile f : images) {
            try {
                totalBytes += (f != null ? f.getSize() : 0L);
            } catch (Exception ignore) {}
        }
        imageBytesSummary.record(totalBytes);

        CreatePostResult result = postMapper.toCreateResult(savedPost, new ArrayList<>());

        totalSample.stop(totalTimer);

        if (!images.isEmpty()) {
            uploadImagesAsync(savedPost, images);
        }

        return result;
    }

    @Async
    protected void uploadImagesAsync(Post post, List<MultipartFile> images) {
        Timer.Sample imgAll = Timer.start(meterRegistry);
        List<String> imageUrls = new ArrayList<>();

        for (MultipartFile image : images) {
            Timer.Sample imgOne = Timer.start(meterRegistry);
            try {
                String imageUrl = s3ImageUploadPort.uploadProductImage(image, post.getId());
                imageUrls.add(imageUrl);

                updatePostWithImage(post.getId(), imageUrl);

            } catch (Exception e) {
                meterRegistry.counter("post.images.upload.error").increment();
                imgOne.stop(imageUploadOneTimer);
                log.error("게시물 이미지 비동기 업로드 실패: postId={}, error={}", post.getId(), e.getMessage(), e);
            }
            imgOne.stop(imageUploadOneTimer);
        }
        imgAll.stop(imageUploadAllTimer);

        log.info("게시물 이미지 비동기 업로드 완료: postId={}, imageCount={}", post.getId(), imageUrls.size());
    }

    @Transactional(timeout = 5)
    protected void updatePostWithImage(Long postId, String imageUrl) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new NoSuchPostException("게시물을 찾을 수 없습니다: " + postId));
        post.addProductImage(imageUrl);
        postRepository.save(post);
    }

    @Override
    @Transactional(readOnly = true)
    public AnalyzeProductImageResponse analyzeProductImage(MultipartFile productImage) {
        return productImageAnalysisPort.analyzeProductImage(productImage);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductConditionAnalysisResponseDto analyzeProductCondition(MultipartFile productImage) {
        return productConditionAnalysisPort.analyzeProductCondition(productImage);
    }

    @Override
    @Transactional(readOnly = true)
    public CombinedProductAnalysisResponse analyzeProduct(MultipartFile productImage) {
        AnalyzeProductImageResponse imageResponse = analyzeProductImage(productImage);
        ProductConditionAnalysisResponseDto conditionResponse = analyzeProductCondition(productImage);
        return CombinedProductAnalysisResponse.from(imageResponse, conditionResponse);
    }

    @Override
    @Transactional(timeout = 5)
    public boolean likePost(Long postId, Long memberId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new NoSuchPostException("ID가 " + postId + "인 게시물이 존재하지 않습니다."));
        if (postLikeRepository.existsByPostAndMemberId(post, memberId)) return false;

        post.addLike(memberId);
        postLikeCountRepository.findById(postId).orElseGet(() -> new PostLikeCount(postId, 0L));
        postLikeCountRepository.incrementLikeCount(postId);
        return true;
    }

    @Override
    @Transactional(timeout = 5)
    public boolean unlikePost(Long postId, Long memberId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new NoSuchPostException("ID가 " + postId + "인 게시물이 존재하지 않습니다."));
        if (!postLikeRepository.existsByPostAndMemberId(post, memberId)) return false;

        post.removeLike(memberId);
        postLikeCountRepository.findById(postId).orElseGet(() -> new PostLikeCount(postId, 0L));
        postLikeCountRepository.decrementLikeCount(postId);
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isPostLikedByMember(Long postId, Long memberId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new NoSuchPostException("ID가 " + postId + "인 게시물이 존재하지 않습니다."));
        return postLikeRepository.existsByPostAndMemberId(post, memberId);
    }

    @Override
    @Transactional(readOnly = true)
    public int getPostLikeCount(Long postId) {
        return postLikeCountRepository.findById(postId)
                .map(PostLikeCount::getLikeCount).map(Long::intValue).orElse(0);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SearchPostResult> getLikedPostsByMember(SearchPostCommand command) {
        return postQueryPort.searchPostsLikedByMember(command);
    }

    @Override
    @Transactional(timeout = 10)
    public UpdatePostResult updatePost(UpdatePostCommand command) {
        Post post = postRepository.findById(command.getPostId())
                .orElseThrow(() -> new NoSuchPostException("ID가 " + command.getPostId() + "인 게시물이 존재하지 않습니다."));
        if (!post.getAuthorId().equals(command.getAuthorId())) {
            throw new IllegalArgumentException("게시물 작성자만 수정할 수 있습니다.");
        }

        Post updatedPost = Post.builder()
                .id(post.getId())
                .title(command.getTitle() != null ? command.getTitle() : post.getTitle())
                .content(command.getContent() != null ? command.getContent() : post.getContent())
                .category(command.getCategory() != null ? command.getCategory() : post.getCategory())
                .rentalFee(command.getRentalFee() != null ? command.getRentalFee() : post.getRentalFee())
                .deposit(command.getDeposit() != null ? command.getDeposit() : post.getDeposit())
                .address(command.getAddress() != null ? command.getAddress() : post.getAddress())
                .latitude(command.getPreferredLocation() != null ? command.getPreferredLocation().getLatitude() : post.getLatitude())
                .longitude(command.getPreferredLocation() != null ? command.getPreferredLocation().getLongitude() : post.getLongitude())
                .authorId(post.getAuthorId())
                .productImages(new ArrayList<>(post.getProductImages()))
                .build();

        updatedPost = postRepository.save(updatedPost);
        long version = updatedPost.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

        PostUpsertEvent evt = PostUpsertEvent.builder()
                .postId(updatedPost.getId()).version(version)
                .title(updatedPost.getTitle()).content(updatedPost.getContent())
                .rentalFee(updatedPost.getRentalFee()).deposit(updatedPost.getDeposit())
                .address(updatedPost.getAddress())
                .lat(updatedPost.getLatitude()).lon(updatedPost.getLongitude())
                .category(updatedPost.getCategory() != null ? updatedPost.getCategory().name() : null)
                .likeCount(getPostLikeCount(updatedPost.getId()))
                .createdAtIso(updatedPost.getCreatedAt().toString())
                .build();

        OutboxEvent ob = new OutboxEvent();
        ob.setAggregateType("POST");
        ob.setAggregateId(updatedPost.getId());
        ob.setEventType("UPSERT");
        try {
            ob.setPayload(objectMapper.writeValueAsString(evt));
        } catch (Exception e) {
            throw new RuntimeException("이벤트 직렬화 실패", e);
        }
        ob.setVersion(version);
        ob.setCreatedAt(updatedPost.getUpdatedAt());
        ob.setPublished(false);

        recordOutboxInsert(() -> outboxRepo.save(ob));

        List<String> imageUrls = new ArrayList<>();

        if (command.getProductImages() == null || command.getProductImages().isEmpty()) {
            updatedPost.getProductImages().forEach(image -> imageUrls.add(image.getImageUrl()));
        } else {
            updatedPost = Post.builder()
                    .id(updatedPost.getId())
                    .title(updatedPost.getTitle())
                    .content(updatedPost.getContent())
                    .category(updatedPost.getCategory())
                    .rentalFee(updatedPost.getRentalFee())
                    .deposit(updatedPost.getDeposit())
                    .address(updatedPost.getAddress())
                    .latitude(updatedPost.getLatitude())
                    .longitude(updatedPost.getLongitude())
                    .authorId(updatedPost.getAuthorId())
                    .productImages(new ArrayList<>())
                    .build();

            updatedPost = postRepository.save(updatedPost);

            List<MultipartFile> images = command.getProductImages();
            if (!images.isEmpty()) {
                uploadImagesAsync(updatedPost, images);
            }
        }

        return postMapper.toUpdateResult(updatedPost, imageUrls);
    }

    @Override
    @Transactional(timeout = 10)
    public boolean deletePost(Long postId, Long userId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new NoSuchPostException("ID가 " + postId + "인 게시물이 존재하지 않습니다."));
        if (!post.getAuthorId().equals(userId)) {
            throw new IllegalArgumentException("게시물 작성자만 삭제할 수 있습니다.");
        }

        postRepository.delete(post);
        postLikeCountRepository.findById(postId).ifPresent(postLikeCountRepository::delete);

        long version = System.currentTimeMillis();
        PostDeleteEvent evt = PostDeleteEvent.builder()
                .postId(postId).version(version).build();

        OutboxEvent ob = new OutboxEvent();
        ob.setAggregateType("POST");
        ob.setAggregateId(postId);
        ob.setEventType("DELETE");
        try {
            ob.setPayload(objectMapper.writeValueAsString(evt));
        } catch (Exception e) {
            throw new RuntimeException("이벤트 직렬화 실패", e);
        }
        ob.setVersion(version);
        ob.setCreatedAt(LocalDateTime.now());
        ob.setPublished(false);

        recordOutboxInsert(() -> outboxRepo.save(ob));

        return true;
    }
}
