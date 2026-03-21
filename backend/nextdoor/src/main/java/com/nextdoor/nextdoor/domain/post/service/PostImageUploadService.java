package com.nextdoor.nextdoor.domain.post.service;

import com.nextdoor.nextdoor.domain.post.domain.Post;
import com.nextdoor.nextdoor.domain.post.exception.NoSuchPostException;
import com.nextdoor.nextdoor.domain.post.port.S3ImageUploadPort;
import com.nextdoor.nextdoor.domain.post.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/**
 * S3 이미지 비동기 업로드 전담 서비스. (SRP)
 * PostCommandService의 비즈니스 흐름에서 I/O 집약적 업로드 작업을 분리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostImageUploadService {

    private final S3ImageUploadPort s3ImageUploadPort;
    private final PostRepository postRepository;
    private final PostMetrics postMetrics;

    @Async
    public void uploadImagesAsync(Post post, List<MultipartFile> images) {
        var allSample = postMetrics.startSample();
        List<String> uploaded = new ArrayList<>();

        for (MultipartFile image : images) {
            var oneSample = postMetrics.startSample();
            try {
                String url = s3ImageUploadPort.uploadProductImage(image, post.getId());
                uploaded.add(url);
                attachImageToPost(post.getId(), url);
            } catch (Exception e) {
                postMetrics.incrementImageUploadError();
                log.error("이미지 업로드 실패: postId={}", post.getId(), e);
            } finally {
                postMetrics.stopImageUploadOne(oneSample);
            }
        }

        postMetrics.stopImageUploadAll(allSample);
        log.info("이미지 업로드 완료: postId={}, count={}", post.getId(), uploaded.size());
    }

    @Transactional(timeout = 5)
    public void attachImageToPost(Long postId, String imageUrl) {
        Post post = postRepository.findByIdAndDeletedFalse(postId)
                .orElseThrow(() -> new NoSuchPostException("게시물을 찾을 수 없습니다: " + postId));
        post.addProductImage(imageUrl);
        postRepository.save(post);
    }
}
