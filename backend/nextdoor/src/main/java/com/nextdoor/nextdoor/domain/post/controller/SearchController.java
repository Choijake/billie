package com.nextdoor.nextdoor.domain.post.controller;

import com.nextdoor.nextdoor.domain.post.controller.dto.PostSearchResponseDto;
import com.nextdoor.nextdoor.domain.search.suggestion.KeywordSuggestionService;
import com.nextdoor.nextdoor.domain.search.document.PostSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
          @RequestParam(value = "keyword", required = false, defaultValue = "") String keyword,
          @RequestParam(value = "address", required = false) String address,
          @RequestParam(value = "page", defaultValue = "0") int page,
          @RequestParam(value = "size", defaultValue = "10") int size,
          @RequestParam(value = "sort", defaultValue = "createdAt") String sort,
          @RequestParam(value = "direction", defaultValue = "DESC") String direction
  ) {
    Sort.Direction sortDirection = "ASC".equalsIgnoreCase(direction)
            ? Sort.Direction.ASC
            : Sort.Direction.DESC;

    PageRequest pageRequest = PageRequest.of(page, size, Sort.by(sortDirection, sort));

    if (keyword != null && !keyword.isBlank()) {
      keywordSuggestionService.saveSearchKeyword(keyword);
    }

    Page<PostSearchResponseDto> results = postSearchService.searchPostsByKeywordInAddress(keyword, address, pageRequest);
    return ResponseEntity.ok(results);
  }

  @GetMapping("/search/suggestions")
  public ResponseEntity<List<String>> getSuggestions(
          @RequestParam(value = "prefix", required = false, defaultValue = "") String prefix
  ) {
    List<String> suggestions = keywordSuggestionService.suggestKeywords(prefix);
    return ResponseEntity.ok(suggestions);
  }
}
