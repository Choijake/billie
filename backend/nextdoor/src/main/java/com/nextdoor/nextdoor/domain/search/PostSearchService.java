package com.nextdoor.nextdoor.domain.search;

import com.nextdoor.nextdoor.domain.post.controller.dto.PostSearchResponseDto;
import com.nextdoor.nextdoor.domain.post.exception.PostSearchException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class PostSearchService {

    private final PostSearchRepository postSearchRepository;

    public Page<PostSearchResponseDto> searchPostsByKeywordInAddress(String keyword, String address, Pageable pageable) {
        try {
            Page<PostDocument> posts = postSearchRepository.searchByKeywordWithAddress(keyword, address, pageable);
            return posts.map(PostSearchResponseDto::from);
        } catch (Exception e) {
            log.error("게시물 검색 중 오류 발생: {}", e.getMessage(), e);
            throw new PostSearchException("게시물 검색 중 오류가 발생했습니다.", e);
        }
    }
}
