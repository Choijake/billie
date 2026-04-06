package com.nextdoor.nextdoor.domain.search.document;

import com.nextdoor.nextdoor.domain.post.controller.dto.PostSearchResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class PostSearchService {

    private final PostSearchRepository postSearchRepository;

    public Page<PostSearchResponseDto> search(SearchRequest request) {
        Page<PostDocument> posts = postSearchRepository.search(request);
        return posts.map(PostSearchResponseDto::from);
    }

    public List<PostSearchResponseDto> autocomplete(String keyword, double lat, double lon) {
        return postSearchRepository.autocomplete(keyword, lat, lon)
                .stream()
                .map(PostSearchResponseDto::from)
                .toList();
    }
}
