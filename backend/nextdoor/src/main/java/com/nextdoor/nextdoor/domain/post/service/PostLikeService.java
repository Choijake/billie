package com.nextdoor.nextdoor.domain.post.service;

import com.nextdoor.nextdoor.domain.post.domain.PostLikeCount;
import com.nextdoor.nextdoor.domain.post.event.PostLikedEvent;
import com.nextdoor.nextdoor.domain.post.exception.NoSuchPostException;
import com.nextdoor.nextdoor.domain.post.port.PostQueryPort;
import com.nextdoor.nextdoor.domain.post.repository.PostLikeCountRepository;
import com.nextdoor.nextdoor.domain.post.repository.PostLikeRepository;
import com.nextdoor.nextdoor.domain.post.repository.PostRepository;
import com.nextdoor.nextdoor.domain.post.service.dto.SearchPostCommand;
import com.nextdoor.nextdoor.domain.post.service.dto.SearchPostResult;
import com.nextdoor.nextdoor.domain.post.service.usecase.PostLikeUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좋아요 도메인 로직에만 집중하는 서비스. (SRP)
 */
@Service
@RequiredArgsConstructor
public class PostLikeService implements PostLikeUseCase {

    private final PostRepository postRepository;
    private final PostLikeRepository postLikeRepository;
    private final PostLikeCountRepository postLikeCountRepository;
    private final PostQueryPort postQueryPort;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(timeout = 5)
    public boolean likePost(Long postId, Long memberId) {
        var post = postRepository.findByIdAndDeletedFalse(postId)
                .orElseThrow(() -> new NoSuchPostException("ID가 " + postId + "인 게시물이 존재하지 않습니다."));
        if (postLikeRepository.existsByPostAndMemberId(post, memberId)) return false;

        post.addLike(memberId);
        postLikeCountRepository.findById(postId).orElseGet(() -> new PostLikeCount(postId, 0L));
        postLikeCountRepository.incrementLikeCount(postId);
        eventPublisher.publishEvent(new PostLikedEvent(memberId, post.getCategory()));
        return true;
    }

    @Override
    @Transactional(timeout = 5)
    public boolean unlikePost(Long postId, Long memberId) {
        var post = postRepository.findByIdAndDeletedFalse(postId)
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
        var post = postRepository.findByIdAndDeletedFalse(postId)
                .orElseThrow(() -> new NoSuchPostException("ID가 " + postId + "인 게시물이 존재하지 않습니다."));
        return postLikeRepository.existsByPostAndMemberId(post, memberId);
    }

    @Override
    @Transactional(readOnly = true)
    public int getPostLikeCount(Long postId) {
        return postLikeCountRepository.findById(postId)
                .map(PostLikeCount::getLikeCount)
                .map(Long::intValue)
                .orElse(0);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SearchPostResult> getLikedPostsByMember(SearchPostCommand command) {
        return postQueryPort.searchPostsLikedByMember(command);
    }
}
