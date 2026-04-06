package com.nextdoor.nextdoor.domain.post.controller;

import com.nextdoor.nextdoor.domain.post.controller.dto.PostSearchResponseDto;
import com.nextdoor.nextdoor.domain.search.document.SearchRequest;
import com.nextdoor.nextdoor.domain.search.document.SearchSortType;
import com.nextdoor.nextdoor.domain.search.document.PostSearchService;
import com.nextdoor.nextdoor.domain.search.suggestion.KeywordSuggestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/posts")
public class SearchController {

    private final PostSearchService postSearchService;
    private final KeywordSuggestionService keywordSuggestionService;

    @GetMapping("/search")
    public ResponseEntity<Page<PostSearchResponseDto>> search(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "lat") double lat,
            @RequestParam(value = "lon") double lon,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "sortType", defaultValue = "RECOMMENDED") String sortType,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "minPrice", required = false) Integer minPrice,
            @RequestParam(value = "maxPrice", required = false) Integer maxPrice
    ) {
        if (keyword != null && !keyword.isBlank()) {
            keywordSuggestionService.saveSearchKeyword(keyword);
        }

        SearchRequest request = SearchRequest.builder()
                .keyword(keyword)
                .lat(lat)
                .lon(lon)
                .sortType(SearchSortType.valueOf(sortType.toUpperCase()))
                .category(category)
                .minPrice(minPrice)
                .maxPrice(maxPrice)
                .pageable(PageRequest.of(page, size))
                .build();

        return ResponseEntity.ok(postSearchService.search(request));
    }

    @GetMapping("/search/suggestions")
    public ResponseEntity<List<PostSearchResponseDto>> getSuggestions(
            @RequestParam("prefix") String prefix,
            @RequestParam("lat") double lat,
            @RequestParam("lon") double lon
    ) {
        return ResponseEntity.ok(postSearchService.autocomplete(prefix, lat, lon));
    }
}
