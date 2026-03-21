package com.nextdoor.nextdoor.domain.post.port;

import com.nextdoor.nextdoor.domain.post.domain.Post;

/**
 * Post 도메인이 검색 인덱싱을 요청하기 위한 아웃바운드 포트.
 * 구현체(OutboxPostIndexAdapter)는 search 모듈에 위치하며,
 * post 모듈은 검색 인프라(Outbox, SQS, ES)를 직접 알지 못한다.
 */
public interface PostIndexPort {

    void requestUpsert(Post post, int likeCount);

    void requestDelete(Long postId);
}
